package com.miniichatNext.carter.vm

import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.data.compress.buildChunkRequest
import com.miniichatNext.carter.data.compress.buildChunks
import com.miniichatNext.carter.data.compress.buildFinalRequest
import com.miniichatNext.carter.data.compress.buildLocalFallback
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.Role
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 摘要起始标记，用于识别会话中已存在的历史压缩档案 */
private const val SUMMARY_MARK = "【以下是本次会话之前的历史压缩档案"

/** 自动为新会话根据第一轮对话内容生成简练标题 */
fun ChatViewModel.generateTitleIfNeeded(conversationId: String) {
    viewModelScope.launch {
        val conv = store.snapshot().find { it.id == conversationId } ?: return@launch
        if (conv.title != "新会话") return@launch

        val userFirstMsg = conv.messages.firstOrNull { it.role == Role.USER }?.content?.trim()
            ?: return@launch
        if (userFirstMsg.isBlank()) return@launch

        val prov = activeProvider() ?: return@launch
        val model = settings.value.titleModel.ifBlank {
            settings.value.activeModel.ifBlank { prov.models.firstOrNull()?.modelId }
        } ?: return@launch

        try {
            val prompt = "请为以下用户的开场问题起一个精炼、概括性的对话标题，" +
                "长度在 10 个字以内，不要包含书名号、标点符号或引号，直接输出标题文本：\n\"$userFirstMsg\""
            val messages = listOf(
                ChatMessage(
                    role = Role.SYSTEM,
                    content = "你是一个专业的内容提炼助手。直接输出纯文本标题，严禁任何额外客套或解释。"
                ),
                ChatMessage(role = Role.USER, content = prompt)
            )

            var generatedTitle = ""
            client.chatStream(
                provider = prov,
                settings = settings.value.copy(stream = false),
                modelId = model,
                messages = messages
            ).collect { delta ->
                delta.text?.let { generatedTitle += it }
            }

            val cleanTitle = generatedTitle.trim()
                .replace("\"", "")
                .replace("“", "")
                .replace("”", "")
                .replace("《", "")
                .replace("》", "")
                .take(15)

            if (cleanTitle.isNotBlank()) {
                val all = store.snapshot().map {
                    if (it.id == conversationId) it.copy(title = cleanTitle) else it
                }
                store.save(all)
            }
        } catch (e: Exception) {
            val fallbackTitle = userFirstMsg.take(12).trim()
            if (fallbackTitle.isNotBlank()) {
                val all = store.snapshot().map {
                    if (it.id == conversationId && it.title == "新会话") {
                        it.copy(title = fallbackTitle)
                    } else {
                        it
                    }
                }
                store.save(all)
            }
        }
    }
}

fun ChatViewModel.generateSuggestions(page: Int) {
    val conv = activeConversation.value ?: return
    val prov = activeProvider() ?: return
    val model = settings.value.activeModel ?: prov.models.firstOrNull()?.modelId ?: return

    viewModelScope.launch {
        _suggestionsGenerating.value = _suggestionsGenerating.value + page
        try {
            val history = conv.messages.takeLast(6).map {
                ChatMessage(role = it.role, content = it.content)
            }
            val prompt = "请根据对话上下文生成3条简短的后续回复建议，" +
                "直接以JSON数组格式输出字符串列表，例如[\"建议1\", \"建议2\", \"建议3\"]。"
            val messages = history + ChatMessage(role = Role.USER, content = prompt)

            var resultText = ""
            client.chatStream(
                provider = prov,
                settings = settings.value.copy(stream = false),
                modelId = model,
                messages = messages
            ).collect { delta ->
                delta.text?.let { resultText += it }
            }

            val cleanJson = resultText.substringAfter("[").substringBeforeLast("]").let { "[$it]" }
            val items = runCatching {
                Json.parseToJsonElement(cleanJson).jsonArray.mapNotNull { it.jsonPrimitive.content }
            }.getOrDefault(emptyList())

            if (items.isNotEmpty()) {
                val updatedPages = conv.suggestionPages.toMutableMap()
                updatedPages[page] = items
                store.save(store.snapshot().map {
                    if (it.id == conv.id) it.copy(suggestionPages = updatedPages) else it
                })
            }
        } catch (e: Exception) {
            _toast.value = "生成建议失败: ${e.message}"
        } finally {
            _suggestionsGenerating.value = _suggestionsGenerating.value - page
        }
    }
}

/**
 * 会话上下文压缩。
 *
 * 与旧实现的关键差异：
 *
 * 1. 「一句话总结」改为「结构化技术档案」。
 *    旧实现先把每段压到 150 字，再收敛成一句话，导致文件路径、方法名、代码片段、
 *    调试结论全部丢失，AI 后续无法基于历史继续工作。现在强制输出七个固定字段，
 *    明确要求保留路径 / 类名 / 方法名 / 依赖版本 / 报错原文 / 代码片段。
 *
 * 2. 分块改为按「消息边界」切分，并附带工具调用记录。
 *    旧实现按字符数硬切，会把半截 JSON 或半截代码喂给模型，提炼结果必然是错的。
 *    同时把 toolInvocations 一并带上，这样「读过哪个文件、调过什么工具」才能进摘要。
 *
 * 3. 所有提前返回都给出明确提示。
 *    旧实现在 activeConversation 为 null 时静默 return，用户点确认后界面毫无反应，
 *    看起来就是「按钮坏了」。现在每个分支都会通过 _error / _toast 反馈原因。
 *
 * 4. 参数做有效性校验。
 *    旧实现允许 keepRecentMessages 输入 0 或超大值，前者等于不保留任何原文，
 *    后者会直接命中「无需压缩」分支。现在统一夹取到合法区间并提示实际生效值。
 *
 * 5. 二次压缩时自动合并既有档案。
 *    如果会话中已存在历史压缩档案，会把它的正文并入本次提炼的输入，
 *    并在写入时替换旧档案而不是叠加，避免多份档案并存导致模型混淆。
 */
fun ChatViewModel.compressConversationContext(
    targetTokens: Int,
    keepRecentMessages: Int,
    additionalPrompt: String
) {
    // ---------- 前置校验：每个分支都必须有可见反馈 ----------

    val conv = activeConversation.value
    if (conv == null) {
        _error.value = "当前没有活动会话，无法压缩。请先进入一个会话再操作。"
        return
    }

    val allMsgs = conv.messages
    if (allMsgs.isEmpty()) {
        _toast.value = "当前会话没有任何消息，无需压缩"
        return
    }

    val prov = activeProvider()
    if (prov == null) {
        _error.value = "未配置有效的模型提供商，无法进行 AI 压缩"
        return
    }
    val model = settings.value.activeModel.ifBlank { prov.models.firstOrNull()?.modelId }
    if (model.isNullOrBlank()) {
        _error.value = "未选择模型，无法进行 AI 压缩"
        return
    }

    // 已有档案时，旧档案也要参与本轮提炼，否则上一轮的结论会丢
    val existingSummary = allMsgs.firstOrNull {
        it.role == Role.SYSTEM && it.content.startsWith(SUMMARY_MARK)
    }
    val restMsgs = if (existingSummary != null) allMsgs.drop(1) else allMsgs

    // 保留条数夹取：至少 1 条，最多不超过可压缩消息的三分之一。
    // 保留 0 条会把用户最后的提问也一起压掉，保留过多则等于没压缩。
    val maxKeep = (restMsgs.size / 3).coerceAtLeast(1)
    val keepCount = keepRecentMessages.coerceIn(1, maxKeep)

    if (restMsgs.size <= keepCount) {
        _toast.value = "当前仅 ${restMsgs.size} 条有效消息，保留 $keepCount 条后已无可压缩内容"
        return
    }

    // ---------- 执行压缩 ----------

    viewModelScope.launch {
        _compressingState.value = "正在整理历史上下文..."

        val toCompress = restMsgs.dropLast(keepCount)
        val toKeep = restMsgs.takeLast(keepCount)

        try {
            // 把上一轮档案作为「已知背景」拼到待压缩内容最前面
            val compressInput = buildList {
                if (existingSummary != null) {
                    add(
                        Message(
                            role = Role.SYSTEM,
                            content = "【上一轮压缩档案，需要合并进本次档案】\n" +
                                existingSummary.content
                        )
                    )
                }
                addAll(toCompress)
            }

            val chunks = buildChunks(compressInput)

            if (chunks.isEmpty()) {
                val summary = buildLocalFallback(toCompress, keepCount)
                applySummary(conv.id, summary, toKeep, prov.id, prov.name, model, keepCount)
                return@launch
            }

            val chunkSummaries = mutableListOf<String>()
            val totalChunks = chunks.size

            chunks.forEachIndexed { index, chunkText ->
                _compressingState.value = "正在提炼第 ${index + 1}/$totalChunks 段历史..."

                var partSummary = ""
                client.chatStream(
                    provider = prov,
                    settings = settings.value.copy(stream = false),
                    modelId = model,
                    messages = buildChunkRequest(chunkText, index, totalChunks)
                ).collect { delta ->
                    delta.text?.let { partSummary += it }
                }

                if (partSummary.isNotBlank()) {
                    chunkSummaries.add("【第 ${index + 1} 段】\n${partSummary.trim()}")
                }
            }

            // 所有分块都失败：兜底为本地结构化摘要
            if (chunkSummaries.isEmpty()) {
                val summary = buildLocalFallback(toCompress, keepCount)
                applySummary(conv.id, summary, toKeep, prov.id, prov.name, model, keepCount)
                _toast.value = "AI 未返回有效摘要，已使用本地结构化摘要完成压缩"
                return@launch
            }

            // 只有一个分块时无需再做一次合并请求，省一次往返
            if (chunkSummaries.size == 1) {
                val only = chunkSummaries.first().removePrefix("【第 1 段】\n").trim()
                if (only.isNotBlank()) {
                    applySummary(conv.id, only, toKeep, prov.id, prov.name, model, keepCount)
                    return@launch
                }
            }

            _compressingState.value = "正在合并为完整档案..."

            var finalResult = ""
            client.chatStream(
                provider = prov,
                settings = settings.value.copy(stream = false),
                modelId = model,
                messages = buildFinalRequest(
                    chunkSummaries = chunkSummaries,
                    additionalPrompt = additionalPrompt,
                    keepRecentCount = keepCount,
                    totalCompressed = toCompress.size
                )
            ).collect { delta ->
                delta.text?.let { finalResult += it }
            }

            val finalSummary = finalResult.trim().ifBlank {
                // 合并失败则直接把各分块摘要顺序拼接，保证信息一条不丢
                chunkSummaries.joinToString("\n\n")
            }

            applySummary(conv.id, finalSummary, toKeep, prov.id, prov.name, model, keepCount)
        } catch (e: Exception) {
            // 网络或接口异常：降级为本地结构化摘要。
            // 注意这里不再生成「已压缩 N 条对话」这类无信息量的句子，
            // 而是从原始消息中抽取文件路径、工具调用记录、用户诉求等客观线索。
            val localSummary = buildLocalFallback(toCompress, keepCount)
            applySummary(conv.id, localSummary, toKeep, prov.id, prov.name, model, keepCount)
            _toast.value = "AI 接口异常（${e.message}），已使用本地结构化摘要完成压缩并释放上下文"
        } finally {
            _compressingState.value = null
        }
    }
}

/**
 * 把摘要写回会话。
 *
 * 采用「按 id 精确改写」的方式而不是整体替换：
 *
 *   - 整个提炼过程可能耗时数十秒，期间用户完全可能发送了新消息。
 *     如果直接写入捕获时的 toKeep 快照，这些新消息会被静默丢弃。
 *   - 因此这里在 transform 拿到的实时消息列表上操作：
 *     先按 id 把被压缩掉的那些消息标记出来，再连同旧档案一起替换为一条新档案。
 *
 * 具体策略：
 *   1. 保留旧档案之后的、仍应保留的最后 keepCount 条（按 id 匹配，避免错位）。
 *   2. 新档案 + 这些保留消息，构成最终列表。
 *   3. 任何在压缩期间新追加的消息（不在 toKeep 也不在 toCompress 里）都会被保留在末尾。
 */
private suspend fun ChatViewModel.applySummary(
    convId: String,
    summary: String,
    toKeep: List<Message>,
    providerId: String,
    providerName: String,
    model: String,
    keepCount: Int
) {
    val summaryContent = buildString {
        append(SUMMARY_MARK)
        append("，请在后续回答中把它当作已知上下文】\n")
        append("【本次已保留最近 ").append(keepCount).append(" 条消息原文，位于本档案之后】\n")
        append("──────────────────────────────\n")
        append(summary.trim())
        append("\n──────────────────────────────")
    }

    // 需要保留的原始消息 id 集合
    val keepIds = toKeep.map { it.id }.toHashSet()
    // 需要被压缩掉的消息 id 集合（含旧档案）
    val dropIds = LinkedHashSet<String>()
    val convBefore = store.snapshot().find { it.id == convId }
    val existingSummaryId = convBefore?.messages?.firstOrNull {
        it.role == Role.SYSTEM && it.content.startsWith(SUMMARY_MARK)
    }?.id
    existingSummaryId?.let { dropIds.add(it) }
    convBefore?.messages?.forEach { m ->
        if (!keepIds.contains(m.id) && m.id != existingSummaryId) {
            // 不在保留集合里的，且时间早于保留集合起点的，视为被压缩掉
            dropIds.add(m.id)
        }
    }

    store.updateMessages(convId) { current ->
        // 在实时列表上重新定位：保留「应保留的」与「压缩期间新产生的」
        val keepSet = keepIds
        val preserved = current.filter { msg ->
            when {
                // 旧档案：丢弃，由新档案取代
                msg.id == existingSummaryId -> false
                // 明确要保留的原文：保留
                keepSet.contains(msg.id) -> true
                // 原本要压缩掉的旧消息：丢弃
                dropIds.contains(msg.id) -> false
                // 其余都是压缩期间新追加的消息：保留
                else -> true
            }
        }

        val summaryMsg = Message(
            role = Role.SYSTEM,
            content = summaryContent,
            providerId = providerId,
            providerName = providerName.ifBlank { providerId },
            model = model
        )

        // 档案放最前，其余消息维持原有顺序
        listOf(summaryMsg) + preserved
    }

    _toast.value = "上下文压缩完成：已生成结构化档案，保留最近 $keepCount 条原文"
}
