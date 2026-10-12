package com.miniichatNext.carter.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.LlmClient
import com.miniichatNext.carter.data.cache.AiResponseCacheManager
import com.miniichatNext.carter.data.mcp.McpAggregateStore
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.data.skills.SkillStore
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.store.AssistantStore
import com.miniichatNext.carter.data.store.ConversationStore
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.data.store.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val store = ConversationStore(application)
    val providerStore = ProviderStore(application)
    val assistantStore = AssistantStore(application)
    val settingsRepo = SettingsRepository(application)
    val skillStore = SkillStore(application)
    val userProfileStore = UserProfileStore(application)
    val mcpStore = McpStore(application)
    val aggregateStore = McpAggregateStore(application)
    val cacheManager = AiResponseCacheManager(application)

    val client: LlmClient = LlmClient()

    val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /**
     * 会话存储是否已完成首次加载。
     *
     * 存在的原因：ConversationStore 的缓存以空列表起步，磁盘内容要等首次访问
     * 才通过 hydrateLocked() 读入。启动阶段若直接把空列表当成「用户没有任何会话」，
     * 会得出错误结论并触发建新会话等副作用。
     * UI 与启动逻辑都应等这个标志为 true 再做判断。
     */
    private val _storeHydrated = MutableStateFlow(false)
    val storeHydrated: StateFlow<Boolean> = _storeHydrated.asStateFlow()

    val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    val _streamingOverlay = MutableStateFlow<String?>(null)
    val streamingOverlay: StateFlow<String?> = _streamingOverlay.asStateFlow()

    val _suggestionsGenerating = MutableStateFlow<Set<Int>>(emptySet())
    val suggestionsGenerating: StateFlow<Set<Int>> = _suggestionsGenerating.asStateFlow()

    val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()

    val _discoveredModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val discoveredModels: StateFlow<Map<String, List<String>>> = _discoveredModels.asStateFlow()

    val _tempSkillOverrides = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
    val tempSkillOverrides: StateFlow<Map<String, Map<String, Boolean>>> = _tempSkillOverrides.asStateFlow()

    val _compressingState = MutableStateFlow<String?>(null)
    val compressingState: StateFlow<String?> = _compressingState.asStateFlow()

    var streamJob: Job? = null

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = settingsRepo.snapshotDirect()
    )

    /**
     * 当前活动会话。
     *
     * 这里刻意不在 _activeId 为 null 时回落到「列表第一个」。
     *
     * 旧实现写的是：
     *     if (id == null) convs.firstOrNull() else convs.find { it.id == id }
     *
     * 启动阶段 _activeId 会有一段时间是 null，此时列表一旦从空变为有内容，
     * 这个分支就会立刻把界面切到第一个历史会话；等 init 的协程真正设置好 _activeId
     * （或用户配置要求新建会话）之后，界面又跳走。用户看到的就是
     * 「闪到历史会话 -> 回首页 -> 又回历史会话」。
     *
     * 现在改为：只有 _activeId 明确指向某条会话时才展示它。
     * 首次该选哪条会话由 init 里的启动流程统一决定，职责单一。
     */
    val activeConversation: StateFlow<Conversation?> =
        combine(store.conversationsFlow, _activeId) { convs, id ->
            if (id == null) null else convs.find { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            skillStore.refresh()

            // ---------- 启动会话初始化（唯一入口） ----------
            //
            // 顺序很重要，每一步都依赖上一步的结果：
            //   1. 先把磁盘内容读进缓存（在此之前 snapshot() 恒为空列表）
            //   2. 拿到真实的会话列表
            //   3. 按用户配置决定本次进入哪条会话
            //
            // 旧实现把「选会话」「清空会话」「自动建会话」分散在三个协程里，
            // 它们互相看不到对方的中间状态，导致空会话被自己创建又被自己删除。
            //
            // 注意：这里不再预先删掉空会话。
            // 空会话分两种：
            //   a) 用户上次退出时留下的「新会话」占位 —— 应该复用，不该删
            //   b) 历史遗留的、用户已经不要了的空白会话 —— 由 selectConversation 顺手清理
            // 启动阶段无法区分这两者，贸然全删会让 autoNewChatOnLaunch 打开时
            // 每次启动都白建一条新会话，会话列表持续堆积。
            val list = store.snapshot()
            _storeHydrated.value = true

            val autoNew = settings.value.autoNewChatOnLaunch
            if (autoNew) {
                // 配置要求每次启动进入新会话。
                // 若已存在一条空会话（上次退出前的占位），直接复用它，
                // 不再新建，避免每次启动都堆一条。
                val existingEmpty = list.firstOrNull { it.messages.isEmpty() }
                if (existingEmpty != null) {
                    _activeId.value = existingEmpty.id
                } else {
                    newConversationInternal(select = true)
                }
            } else {
                // 恢复到最近使用的一条。
                // ConversationStore 未对列表排序，这里按 updatedAt 取最新，
                // 保证用户看到的是上次真正在用的那条，而不是磁盘键顺序里的第一条。
                val withMessages = list.filter { it.messages.isNotEmpty() }
                val target = (withMessages.ifEmpty { list }).maxByOrNull { it.updatedAt }
                if (target != null) {
                    _activeId.value = target.id
                } else {
                    // 一条会话都没有：建一条空会话并选中
                    newConversationInternal(select = true)
                }
            }
        }
    }

    /**
     * 创建一个新会话并（可选）立即选中。
     *
     * 不做 store.snapshot().toMutableList() 再 save 全量：
     * 全量 save 会重写每个会话的 key，在流式写盘并发时是不必要的放大。
     * 这里改用 upsert 只写新增的那一条。
     */
    internal suspend fun newConversationInternal(select: Boolean) {
        val conv = Conversation(
            title = "新会话",
            assistantId = settings.value.activeAssistantId.ifBlank { "default" }
        )
        store.upsert(conv)
        if (select) {
            _activeId.value = conv.id
        }
    }

    fun dismissToast() { _toast.value = null }
    fun dismissError() { _error.value = null }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            // 切换会话时顺带清理无消息的空会话，但绝不清理即将选中的那条。
            // 这里是清理空会话的正确时机：用户明确表达了「要切到某条会话」，
            // 其余空白占位就确实是废弃产物了。
            val list = store.snapshot()
            val filtered = list.filter { it.id == id || it.messages.isNotEmpty() }
            if (filtered.size != list.size) {
                store.save(filtered)
            }
            _activeId.value = id
        }
    }

    fun activeProvider(): ProviderConfig? {
        val pid = settings.value.activeProviderId
        if (pid.isBlank()) return null
        return runBlocking { providerStore.snapshot().find { it.id == pid } }
    }

    fun activeAssistant(): Assistant? {
        val aid = settings.value.activeAssistantId
        return runBlocking { assistantStore.snapshot().find { it.id == aid } }
    }

    fun selectAssistant(assistantId: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeAssistantId = assistantId) }
        }
    }

    fun saveAssistant(assistant: Assistant) {
        viewModelScope.launch {
            assistantStore.upsert(assistant)
        }
    }

    fun saveProvider(provider: ProviderConfig) {
        viewModelScope.launch {
            providerStore.upsert(provider)
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerStore.delete(id)
        }
    }

    fun setActiveModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = modelId) }
        }
    }

    fun setSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(systemPrompt = prompt) } }
    }

    fun setTemperature(temp: Float) {
        viewModelScope.launch { settingsRepo.update { it.copy(temperature = temp) } }
    }

    fun setStream(stream: Boolean) {
        viewModelScope.launch { settingsRepo.update { it.copy(stream = stream) } }
    }

    fun setDynamicColor(dc: Boolean) {
        viewModelScope.launch {
            settingsRepo.update {
                // 动态取色与自定义主题色互斥：开启动态取色则清空自定义色。
                if (dc) it.copy(dynamicColor = true, accentColor = "")
                else it.copy(dynamicColor = false)
            }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(themeMode = mode) } }
    }

    /** 设置自定义主题色，传 #RRGGBB；传空串表示清除自定义色（回落到默认/动态）。 */
    fun setAccentColor(hex: String) {
        viewModelScope.launch {
            settingsRepo.update {
                // 选定自定义色时自动关闭动态取色，两者互斥。
                if (hex.isEmpty()) it.copy(accentColor = "")
                else it.copy(accentColor = hex, dynamicColor = false)
            }
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(language = lang) } }
    }

    fun updateUserProfile(profile: UserProfile) {
        viewModelScope.launch { userProfileStore.save(profile) }
    }
}
