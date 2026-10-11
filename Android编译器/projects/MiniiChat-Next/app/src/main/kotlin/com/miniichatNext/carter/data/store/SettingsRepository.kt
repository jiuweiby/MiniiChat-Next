package com.miniichatNext.carter.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import com.miniichatNext.carter.data.model.TokenUsage

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
data class AppSettings(
    val activeProviderId: String = "",
    val activeModel: String = "",
    val activeAssistantId: String = "default",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",
    val accentColor: String = "",               // 自定义主题色 #RRGGBB，空串表示未启用
    val ocrModel: String = "",
    val titleModel: String = "",
    val compressModel: String = "",
    val suggestionModel: String = "",
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    // 个性化设置选项
    val fontSizeSp: Float = 15f,
    val showReasoningCard: Boolean = true,
    val showToolInvocationCard: Boolean = true,
    val showCardTokenUsage: Boolean = true,
    val sendOnEnter: Boolean = false,
    val pasteAsAttachment: Boolean = false,
    val codeWrap: Boolean = true,
    val codeShowLineNumbers: Boolean = true,
    val codeShowCopyBtn: Boolean = true,
    val keepAliveServiceEnabled: Boolean = false,
    val aiCacheMode: String = "关闭",
    val autoNewChatOnLaunch: Boolean = true      // 默认开启：每次打开软件自动创建新会话
)

class SettingsRepository(private val context: Context) {

    private val updateMutex = Mutex()

    private object Keys {
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val ASSISTANT = stringPreferencesKey("active_assistant_id")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_color")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val TOTAL_PROMPT = intPreferencesKey("total_prompt_tokens")
        val TOTAL_COMPLETION = intPreferencesKey("total_completion_tokens")
        // 个性化设置 keys
        val FONT_SIZE = floatPreferencesKey("pref_font_size_sp")
        val SHOW_REASONING = booleanPreferencesKey("pref_show_reasoning")
        val SHOW_TOOL_INVOCATION = booleanPreferencesKey("pref_show_tool_invocation")
        val SHOW_TOKEN_USAGE = booleanPreferencesKey("pref_show_token_usage")
        val SEND_ON_ENTER = booleanPreferencesKey("pref_send_on_enter")
        val PASTE_ATTACHMENT = booleanPreferencesKey("pref_paste_attachment")
        val CODE_WRAP = booleanPreferencesKey("pref_code_wrap")
        val CODE_LINE_NUM = booleanPreferencesKey("pref_code_line_num")
        val CODE_COPY_BTN = booleanPreferencesKey("pref_code_copy_btn")
        val KEEP_ALIVE = booleanPreferencesKey("pref_keep_alive")
        val AI_CACHE_MODE = stringPreferencesKey("pref_ai_cache_mode")
        val AUTO_NEW_CHAT_ON_LAUNCH = booleanPreferencesKey("pref_auto_new_chat_on_launch")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> read(p) }

    suspend fun snapshot(): AppSettings = settings.first()

    fun snapshotDirect(): AppSettings = runBlocking { settings.first() }

    private fun read(p: Preferences) = AppSettings(
        activeProviderId = p[Keys.PROVIDER] ?: "",
        activeModel = p[Keys.MODEL] ?: "",
        activeAssistantId = p[Keys.ASSISTANT] ?: "default",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        accentColor = p[Keys.ACCENT] ?: "",
        ocrModel = p[Keys.OCR_MODEL] ?: "",
        titleModel = p[Keys.TITLE_MODEL] ?: "",
        compressModel = p[Keys.COMPRESS_MODEL] ?: "",
        suggestionModel = p[Keys.SUGGESTION_MODEL] ?: "",
        totalPromptTokens = p[Keys.TOTAL_PROMPT] ?: 0,
        totalCompletionTokens = p[Keys.TOTAL_COMPLETION] ?: 0,
        fontSizeSp = p[Keys.FONT_SIZE] ?: 15f,
        showReasoningCard = p[Keys.SHOW_REASONING] ?: true,
        showToolInvocationCard = p[Keys.SHOW_TOOL_INVOCATION] ?: true,
        showCardTokenUsage = p[Keys.SHOW_TOKEN_USAGE] ?: true,
        sendOnEnter = p[Keys.SEND_ON_ENTER] ?: false,
        pasteAsAttachment = p[Keys.PASTE_ATTACHMENT] ?: false,
        codeWrap = p[Keys.CODE_WRAP] ?: true,
        codeShowLineNumbers = p[Keys.CODE_LINE_NUM] ?: true,
        codeShowCopyBtn = p[Keys.CODE_COPY_BTN] ?: true,
        keepAliveServiceEnabled = p[Keys.KEEP_ALIVE] ?: false,
        aiCacheMode = p[Keys.AI_CACHE_MODE] ?: "关闭",
        autoNewChatOnLaunch = p[Keys.AUTO_NEW_CHAT_ON_LAUNCH] ?: true // 默认开启
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) = updateMutex.withLock {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.ASSISTANT] = next.activeAssistantId
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.ACCENT] = next.accentColor
            p[Keys.OCR_MODEL] = next.ocrModel
            p[Keys.TITLE_MODEL] = next.titleModel
            p[Keys.COMPRESS_MODEL] = next.compressModel
            p[Keys.SUGGESTION_MODEL] = next.suggestionModel
            p[Keys.TOTAL_PROMPT] = next.totalPromptTokens
            p[Keys.TOTAL_COMPLETION] = next.totalCompletionTokens
            p[Keys.FONT_SIZE] = next.fontSizeSp
            p[Keys.SHOW_REASONING] = next.showReasoningCard
            p[Keys.SHOW_TOOL_INVOCATION] = next.showToolInvocationCard
            p[Keys.SHOW_TOKEN_USAGE] = next.showCardTokenUsage
            p[Keys.SEND_ON_ENTER] = next.sendOnEnter
            p[Keys.PASTE_ATTACHMENT] = next.pasteAsAttachment
            p[Keys.CODE_WRAP] = next.codeWrap
            p[Keys.CODE_LINE_NUM] = next.codeShowLineNumbers
            p[Keys.CODE_COPY_BTN] = next.codeShowCopyBtn
            p[Keys.KEEP_ALIVE] = next.keepAliveServiceEnabled
            p[Keys.AI_CACHE_MODE] = next.aiCacheMode
            p[Keys.AUTO_NEW_CHAT_ON_LAUNCH] = next.autoNewChatOnLaunch
        }
    }

    suspend fun save(next: AppSettings) = update { next }

    suspend fun restore(next: AppSettings) = update { next }

    suspend fun addTokenUsage(usage: TokenUsage) {
        if (usage.promptTokens == 0 && usage.completionTokens == 0) return
        update {
            it.copy(
                totalPromptTokens = it.totalPromptTokens + usage.promptTokens,
                totalCompletionTokens = it.totalCompletionTokens + usage.completionTokens
            )
        }
    }
}
