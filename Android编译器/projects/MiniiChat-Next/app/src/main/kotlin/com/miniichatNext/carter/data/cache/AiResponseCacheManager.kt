package com.miniichatNext.carter.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.api.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.aiCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_cache_store")

@Serializable
data class CacheStats(
    val totalRequests: Int = 0,
    val hitCount: Int = 0,
    val savedPromptTokens: Int = 0,
    val savedCompletionTokens: Int = 0
) {
    val hitRatePercent: Float
        get() = if (totalRequests > 0) (hitCount.toFloat() / totalRequests) * 100f else 0f

    val totalSavedTokens: Int
        get() = savedPromptTokens + savedCompletionTokens
}

data class CompressionResult(
    val processedMessages: List<ChatMessage>,
    val aliasMap: Map<String, String>,
    val savedTokens: Int,
    val isHit: Boolean
)

/**
 * 真正的 Token 缓存与重复上下文词条符号化压缩管理器：
 * 扫描历史对话中多次重复出现的长句子、代码片段或专业名词（如出现多次的"123"映射为代称"§1"），
 * 在系统上下文内定义映射表，并将后续历史消息中重复出现的内容替换为极短的代符号，
 * 显著减少向模型传输的 Prompt Context Tokens。
 * 在接收到回复时自动反向还原，保持用户端体验无缝。
 */
class AiResponseCacheManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    object Keys {
        val STATS = stringPreferencesKey("cache_stats_json")
    }

    val statsFlow: Flow<CacheStats> = context.aiCacheDataStore.data.map { prefs ->
        val raw = prefs[Keys.STATS]
        if (raw.isNullOrBlank()) CacheStats()
        else runCatching { json.decodeFromString<CacheStats>(raw) }.getOrDefault(CacheStats())
    }

    suspend fun getStats(): CacheStats = statsFlow.first()

    /**
     * 根据模式（小、中、大、关闭）对上下文进行高频重复词条提取并替换为别名
     */
    suspend fun compressContext(
        mode: String,
        messages: List<ChatMessage>
    ): CompressionResult {
        val normalizedMode = mode.trim().lowercase()
        if (normalizedMode == "关闭" || normalizedMode == "off" || messages.size < 2) {
            recordRequest(isHit = false, savedTokens = 0)
            return CompressionResult(messages, emptyMap(), 0, false)
        }

        // 阈值配置：小(保守)、中(平衡)、大(激进)
        val (minLen, minOccurrences, maxAliases) = when (normalizedMode) {
            "小", "small" -> Triple(24, 3, 5)
            "中", "medium" -> Triple(12, 2, 12)
            "大", "large" -> Triple(6, 2, 25)
            else -> Triple(16, 2, 10)
        }

        // 1. 搜集候选高频长串（按标点、换行与空格切分片段）
        val phraseCounts = HashMap<String, Int>()
        val textPool = messages.map { it.content }

        for (text in textPool) {
            if (text.length < minLen) continue
            // 提取连续子句（按换行或常见标点划分）
            val segments = text.split(Regex("[\n,，.。;；!！?？:：]"))
                .map { it.trim() }
                .filter { it.length in minLen..400 }

            for (seg in segments) {
                phraseCounts[seg] = (phraseCounts[seg] ?: 0) + 1
            }
        }

        // 2. 筛选出达到阈值的重复片段，优先选长词、高频项
        val eligiblePhrases = phraseCounts.entries
            .filter { it.value >= minOccurrences }
            .sortedByDescending { it.key.length * it.value }
            .take(maxAliases)
            .map { it.key }

        if (eligiblePhrases.isEmpty()) {
            recordRequest(isHit = false, savedTokens = 0)
            return CompressionResult(messages, emptyMap(), 0, false)
        }

        // 3. 构建别名表，格式如 ⟦A1⟧, ⟦A2⟧...
        val aliasMap = LinkedHashMap<String, String>() // alias -> originalText
        val reverseMap = LinkedHashMap<String, String>() // originalText -> alias
        eligiblePhrases.forEachIndexed { idx, phrase ->
            val alias = "⟦${(idx + 1)}⟧"
            aliasMap[alias] = phrase
            reverseMap[phrase] = alias
        }

        // 4. 替换 messages 里的重复内容，并统计节省的字符/预估 Token
        var rawCharsBefore = 0
        var rawCharsAfter = 0

        val newMessages = messages.mapIndexed { index, chatMsg ->
            rawCharsBefore += chatMsg.content.length
            var replacedText = chatMsg.content

            // 对历史消息执行代称替换（若为系统提示词则不替换，但需附加上代称词典声明）
            if (chatMsg.role != "system") {
                reverseMap.forEach { (phrase, alias) ->
                    replacedText = replacedText.replace(phrase, alias)
                }
            }
            rawCharsAfter += replacedText.length

            if (index == 0 && chatMsg.role == "system") {
                val dictPrompt = buildString {
                    append(replacedText)
                    append("\n\n[上下文Token缓存词典]: ")
                    aliasMap.forEach { (al, orig) ->
                        append("$al=\"$orig\" ")
                    }
                    append("。历史上下文中上述代符号均指代对应原词内容。请理解其语义，在回答用户时使用正常的完整词汇。")
                }
                chatMsg.copy(content = dictPrompt)
            } else {
                chatMsg.copy(content = replacedText)
            }
        }

        // 如果第一条不是系统提示词，补充一条说明
        val finalMessages = if (newMessages.none { it.role == "system" }) {
            val dictHeader = buildString {
                append("[上下文Token缓存词典]: ")
                aliasMap.forEach { (al, orig) ->
                    append("$al=\"$orig\" ")
                }
                append("。历史上下文中上述代符号均指代对应原词内容。在回答时请自然展开回答。")
            }
            listOf(ChatMessage(role = "system", content = dictHeader)) + newMessages
        } else {
            newMessages
        }

        val charDiff = (rawCharsBefore - rawCharsAfter).coerceAtLeast(0)
        val savedTokens = (charDiff / 2).coerceAtLeast(0)
        val isHit = savedTokens > 0

        recordRequest(isHit = isHit, savedTokens = savedTokens)

        return CompressionResult(
            processedMessages = finalMessages,
            aliasMap = aliasMap,
            savedTokens = savedTokens,
            isHit = isHit
        )
    }

    /**
     * 反向还原：若模型在输出中携带了代称符号，自动还原为真实原词
     */
    fun decompressText(text: String, aliasMap: Map<String, String>): String {
        if (aliasMap.isEmpty() || text.isEmpty()) return text
        var out = text
        aliasMap.forEach { (alias, original) ->
            out = out.replace(alias, original)
        }
        return out
    }

    private suspend fun recordRequest(isHit: Boolean, savedTokens: Int) {
        context.aiCacheDataStore.edit { p ->
            val cur = runCatching {
                p[Keys.STATS]?.let { json.decodeFromString<CacheStats>(it) }
            }.getOrNull() ?: CacheStats()

            val updated = cur.copy(
                totalRequests = cur.totalRequests + 1,
                hitCount = if (isHit) cur.hitCount + 1 else cur.hitCount,
                savedPromptTokens = cur.savedPromptTokens + savedTokens
            )
            p[Keys.STATS] = json.encodeToString(updated)
        }
    }

    suspend fun clearAll() {
        context.aiCacheDataStore.edit { p ->
            p.remove(Keys.STATS)
        }
    }
}
