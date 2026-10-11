package com.miniichatNext.carter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.provider.ProviderTester
import com.miniichatNext.carter.debug.ui.DebugScreen
import com.miniichatNext.carter.debug.ui.LogPreviewScreen
import com.miniichatNext.carter.ui.assistant.AssistantEditorScreen
import com.miniichatNext.carter.ui.assistant.AssistantsScreen
import com.miniichatNext.carter.ui.chat.ChatScreen
import com.miniichatNext.carter.ui.chat.ModelPickerBottomSheet
import com.miniichatNext.carter.ui.mcp.McpManageScreen
import com.miniichatNext.carter.ui.mcp.McpServerDetailScreen
import com.miniichatNext.carter.ui.provider.ProviderEditorScreen
import com.miniichatNext.carter.ui.provider.ProvidersScreen
import com.miniichatNext.carter.ui.settings.AboutScreen
import com.miniichatNext.carter.ui.settings.BackupScreen
import com.miniichatNext.carter.ui.settings.PreferenceScreen
import com.miniichatNext.carter.ui.settings.SettingsScreen
import com.miniichatNext.carter.ui.settings.ThemeColorPickerScreen
import com.miniichatNext.carter.ui.settings.UserProfileScreen
import com.miniichatNext.carter.ui.skills.SkillsScreen
import com.miniichatNext.carter.vm.ChatViewModel
import com.miniichatNext.carter.vm.approveToolCall
import com.miniichatNext.carter.vm.compressConversationContext
import com.miniichatNext.carter.vm.deleteConversation
import com.miniichatNext.carter.vm.deleteMessage
import com.miniichatNext.carter.vm.editMessage
import com.miniichatNext.carter.vm.effectiveSkillIds
import com.miniichatNext.carter.vm.fetchModelsForProvider
import com.miniichatNext.carter.vm.generateSuggestions
import com.miniichatNext.carter.vm.newConversation
import com.miniichatNext.carter.vm.regenerate
import com.miniichatNext.carter.vm.rejectToolCall
import com.miniichatNext.carter.vm.renameConversation
import com.miniichatNext.carter.vm.send
import com.miniichatNext.carter.vm.stopGeneration
import com.miniichatNext.carter.vm.toggleSkill
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Chat : Screen
    data object Providers : Screen
    data class EditProvider(val provider: ProviderConfig?) : Screen
    data object Settings : Screen
    data object Preference : Screen
    data object Profile : Screen
    data object Assistants : Screen
    data class EditAssistant(val assistant: Assistant?) : Screen
    data object Skills : Screen
    data object McpManage : Screen
    data class McpDetail(val serverId: String) : Screen
    data object Backup : Screen
    data object About : Screen

    /** 主题色取色画布 */
    data object ThemeColorPicker : Screen

    /** 调试面板：日志等级、导出、崩溃测试 */
    data object Debug : Screen

    /** 日志预览：应用内直接查看 / 筛选 / 复制日志 */
    data object LogPreview : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }

    val screenBackStack = remember { mutableStateListOf<Screen>(Screen.Chat) }
    val currentScreen = screenBackStack.lastOrNull() ?: Screen.Chat

    fun navigateTo(screen: Screen) {
        screenBackStack.add(screen)
    }

    fun popScreen(): Boolean {
        return if (screenBackStack.size > 1) {
            screenBackStack.removeAt(screenBackStack.lastIndex)
            true
        } else {
            false
        }
    }

    var lastBackPressTime by remember { mutableStateOf(0L) }
    val modelPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showModelPicker by remember { mutableStateOf(false) }
    var pickingForTitleModel by remember { mutableStateOf(false) }

    // ---------------------------------------------------------------
    // 提供商连通性测试状态
    //
    // 提到 AppRoot 层持有的原因：
    //   一轮完整测试包含三次请求，最坏情况下耗时接近一分钟。
    //   期间用户完全可能切页或进入模型编辑层。
    //   若状态挂在 ProviderEditorScreen 内部，切页会直接丢弃已有结果，
    //   用户回来看到的又是「未测试」，等于白跑一轮。
    // ---------------------------------------------------------------
    val testResults = remember {
        mutableStateMapOf<ProviderTester.Kind, ProviderTester.Result>()
    }
    var testRunning by remember { mutableStateOf(false) }
    var testRunningKind by remember { mutableStateOf<ProviderTester.Kind?>(null) }

    fun startProviderTest(provider: ProviderConfig, modelId: String) {
        if (testRunning) return
        if (modelId.isBlank()) {
            scope.launch { snackbar.showSnackbar("请先为该提供商添加至少一个模型，再执行测试") }
            return
        }
        testResults.clear()
        testRunning = true
        testRunningKind = ProviderTester.Kind.STREAM

        scope.launch {
            try {
                ProviderTester.runAll(
                    provider = provider,
                    modelId = modelId,
                    settings = vm.settings.value
                ) { result ->
                    testResults[result.kind] = result
                    // 进度指针推到下一项，最后一项完成后归位为 null
                    testRunningKind = when (result.kind) {
                        ProviderTester.Kind.STREAM -> ProviderTester.Kind.NON_STREAM
                        ProviderTester.Kind.NON_STREAM -> ProviderTester.Kind.TOOL_CALL
                        ProviderTester.Kind.TOOL_CALL -> null
                    }
                }
            } finally {
                testRunning = false
                testRunningKind = null
            }
        }
    }

    BackHandler(enabled = true) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (showModelPicker) {
            showModelPicker = false
        } else if (!popScreen()) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                (ctx as? android.app.Activity)?.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(ctx, "再次返回退出应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val convs by vm.store.conversationsFlow.collectAsState(initial = emptyList())
    val currentConv by vm.activeConversation.collectAsState(initial = null)
    val storeHydrated by vm.storeHydrated.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providerStore.providersFlow.collectAsState(initial = emptyList())
    val assistants by vm.assistantStore.assistantsFlow.collectAsState(initial = emptyList())
    val userProfile by vm.userProfileStore.profileFlow.collectAsState(initial = UserProfile())
    val skills by vm.skillStore.skillsFlow.collectAsState(initial = emptyList())
    val effectiveSkillIds by vm.effectiveSkillIds(currentConv?.id).collectAsState()
    val mcpServers by vm.mcpStore.serversFlow.collectAsState(initial = emptyList())

    val isStreaming by vm.isStreaming.collectAsState()
    val streamingOverlayText by vm.streamingOverlay.collectAsState()
    val suggestionsGenerating by vm.suggestionsGenerating.collectAsState()
    val fetchingModelsFor by vm.fetchingModelsFor.collectAsState()
    val discoveredModels by vm.discoveredModels.collectAsState()
    val compressingState by vm.compressingState.collectAsState()

    val toastMsg by vm.toast.collectAsState()
    val errorMsg by vm.error.collectAsState()

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            snackbar.showSnackbar(it)
            vm.dismissToast()
        }
    }

    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    // ---------------------------------------------------------------
    // 启动阶段的会话选择
    //
    // 这里刻意不做任何事，全部交给 ChatViewModel.init。
    //
    // 旧实现在这里监听 settings.autoNewChatOnLaunch 并可能调用 newConversation()，
    // 与 init 里的初始化完全并行且互相看不见对方的中间状态，导致
    // 「闪到历史会话 -> 回首页 -> 又回历史会话」。
    // 现在会话选择只有一个入口，配置读取、空会话清理、目标会话决定
    // 都在那一个协程里按顺序完成。
    // ---------------------------------------------------------------

    // 派生值用 remember 收敛：
    // 仅当依赖的列表或 id 变化时才重新遍历查找，
    // 避免 AppRoot 每次重组（任一顶层 state 变化都会触发）都做 O(n) 的 list.find。
    val activeAssistant = remember(assistants, settings.activeAssistantId) {
        assistants.find { it.id == settings.activeAssistantId }
            ?: assistants.firstOrNull()
    }
    val activeProvider = remember(providers, settings.activeProviderId) {
        providers.find { it.id == settings.activeProviderId }
            ?: providers.firstOrNull()
    }

    // 流式覆盖对：只依赖「会话 id + 最后一条消息 id + overlay 文本」，
    // 不再依赖 messages 列表引用。
    // 旧实现用 currentConv?.messages 作为 key，由于流式每 250~800ms 就会写入一次
    // 新列表对象，导致 overlayPair 每帧都判定为"变了"，进而让 ChatScreen 整树重组。
    val lastMsgIdOfConv = currentConv?.messages?.lastOrNull()?.id
    val overlayPair = remember(currentConv?.id, lastMsgIdOfConv, streamingOverlayText) {
        if (streamingOverlayText != null && lastMsgIdOfConv != null) {
            Pair(lastMsgIdOfConv, streamingOverlayText!!)
        } else {
            null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentScreen is Screen.Chat,
        drawerContent = {
            GlassDrawer(
                conversations = convs,
                activeId = currentConv?.id,
                assistants = assistants,
                activeAssistantId = settings.activeAssistantId,
                onSelectAssistant = { aid ->
                    vm.selectAssistant(aid)
                    scope.launch { drawerState.close() }
                },
                onSelect = { id ->
                    vm.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNew = {
                    vm.newConversation()
                    scope.launch { drawerState.close() }
                },
                onDelete = { id ->
                    vm.deleteConversation(id)
                },
                onRename = { id, title ->
                    vm.renameConversation(id, title)
                },
                onOpenAssistants = {
                    navigateTo(Screen.Assistants)
                    scope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    navigateTo(Screen.Settings)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    // 关闭尺寸变换：AnimatedContent 默认会对进出内容做尺寸插值 +
                    // 裁剪，当两个屏幕高度/宽度不同（如设置页与聊天页）时，
                    // 每帧都要测量并缩放整个子树，是「切页卡顿」的直接来源。
                    // 我们只需要透明度过渡，因此显式关闭 SizeTransform。
                    (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false))
                },
                // contentKey 让 Compose 按「屏幕标识」而非「内容实例」跟踪动画目标：
                //   - 同一屏幕内 AppRoot 重组（如流式期间 settings/messages 变化）不会触发进出场动画
                //   - 不同屏幕切换时才做淡入淡出
                // 旧实现无 contentKey，AnimatedContent 会以 target 引用做相等判断，
                // 每次 AppRoot 重组生成的分支 lambda 若引用不相等，可能被误判为「目标变化」而重放动画。
                // 这里直接以 target 自身作 key：data object 是单例、data class 按内容相等，
                // 语义稳定且无需额外维护映射表。
                contentKey = { it },
                label = "ScreenTransition"
            ) { target ->
                when (target) {
                    is Screen.Chat -> {
                        ChatScreen(
                            conversation = currentConv,
                            settings = settings,
                            activeProvider = activeProvider,
                            isStreaming = isStreaming,
                            streamingOverlay = overlayPair,
                            assistant = activeAssistant,
                            userProfile = userProfile,
                            assistants = assistants,
                            skills = skills,
                            effectiveSkillIds = effectiveSkillIds,
                            // 存储尚未完成首次加载时，聊天区显示加载态而不是空态。
                            // 否则启动瞬间会先渲染一次「开始新对话」欢迎页，
                            // 等磁盘数据到达后再切到真实会话，产生可见闪动。
                            initializing = !storeHydrated,
                            onMenu = { scope.launch { drawerState.open() } },
                            onSend = { text, atts -> vm.send(text, atts) },
                            onStop = { vm.stopGeneration() },
                            onNew = {
                                vm.newConversation()
                                scope.launch { drawerState.close() }
                            },
                            onOpenSettings = { navigateTo(Screen.Settings) },
                            onPickModel = {
                                pickingForTitleModel = false
                                showModelPicker = true
                            },
                            onApproveTool = { inv, customResult -> vm.approveToolCall(inv, customResult) },
                            onRejectTool = { inv -> vm.rejectToolCall(inv) },
                            onRegenerateFrom = { msgId -> vm.regenerate(msgId) },
                            onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                            onEditMessage = { msgId, text -> vm.editMessage(msgId, text) },
                            onToggleSkill = { sid, enabled -> vm.toggleSkill(sid, enabled) },
                            onContextCompress = { targetTokens, keepRecent, additionalPrompt ->
                                vm.compressConversationContext(
                                    targetTokens, keepRecent, additionalPrompt
                                )
                            },
                            compressingProgressText = compressingState,
                            onSuggestions = { page -> vm.generateSuggestions(page) },
                            suggestionsGenerating = suggestionsGenerating,
                            onRenameChat = { title ->
                                currentConv?.id?.let { vm.renameConversation(it, title) }
                            },
                            onSelectAssistant = { aid ->
                                vm.selectAssistant(aid)
                            }
                        )
                    }

                    is Screen.Providers -> {
                        ProvidersScreen(
                            providers = providers,
                            fetchingId = fetchingModelsFor,
                            discoveredModels = discoveredModels,
                            activeProviderId = settings.activeProviderId,
                            activeModel = settings.activeModel,
                            onBack = { popScreen() },
                            onCreate = { navigateTo(Screen.EditProvider(null)) },
                            onEdit = { p -> navigateTo(Screen.EditProvider(p)) },
                            onDelete = { pid -> vm.deleteProvider(pid) },
                            onFetchModels = { pid -> vm.fetchModelsForProvider(pid) },
                            onAddManualModel = { pid, modelId ->
                                val p = providers.find { it.id == pid } ?: return@ProvidersScreen
                                val cur = p.models.toMutableList()
                                if (cur.none { it.modelId == modelId }) {
                                    cur.add(ModelConfig(modelId = modelId, displayName = modelId))
                                    vm.saveProvider(p.copy(models = cur))
                                }
                            },
                            onRemoveModel = { pid, modelId ->
                                val p = providers.find { it.id == pid } ?: return@ProvidersScreen
                                val cur = p.models.filterNot { it.modelId == modelId }
                                vm.saveProvider(p.copy(models = cur))
                            },
                            onSelectModel = { pid, modelId ->
                                vm.setActiveModel(pid, modelId)
                            },
                            onEditModel = { pid, model ->
                                // 列表页点击模型：进入该提供商的编辑页并直达模型层
                                navigateTo(Screen.EditProvider(providers.find { it.id == pid }))
                            }
                        )
                    }

                    is Screen.EditProvider -> {
                        ProviderEditorScreen(
                            initial = target.provider,
                            onSave = { p ->
                                vm.saveProvider(p)
                                popScreen()
                            },
                            onCancel = { popScreen() },
                            testRunning = testRunning,
                            testRunningKind = testRunningKind,
                            testResults = testResults,
                            onRunTest = { cfg, modelId -> startProviderTest(cfg, modelId) }
                        )
                    }

                    is Screen.Settings -> {
                        SettingsScreen(
                            settings = settings,
                            userProfile = userProfile,
                            providers = providers,
                            mcpServers = mcpServers,
                            onSetSystemPrompt = { vm.setSystemPrompt(it) },
                            onSetTemperature = { vm.setTemperature(it) },
                            onSetStream = { vm.setStream(it) },
                            onSetDynamicColor = { vm.setDynamicColor(it) },
                            onSetThemeMode = { vm.setThemeMode(it) },
                            onOpenThemeColorPicker = { navigateTo(Screen.ThemeColorPicker) },
                            onSetLanguage = { vm.setLanguage(it) },
                            onOpenProviders = { navigateTo(Screen.Providers) },
                            onOpenMcp = { navigateTo(Screen.McpManage) },
                            onOpenBackup = { navigateTo(Screen.Backup) },
                            onOpenAbout = { navigateTo(Screen.About) },
                            onOpenProfile = { navigateTo(Screen.Profile) },
                            onOpenPreference = { navigateTo(Screen.Preference) },
                            onOpenTitleModelPicker = {
                                pickingForTitleModel = true
                                showModelPicker = true
                            },
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.ThemeColorPicker -> {
                        ThemeColorPickerScreen(
                            initialColor = settings.accentColor,
                            onConfirm = { hex ->
                                vm.setAccentColor(hex)
                                popScreen()
                            },
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.Preference -> {
                        PreferenceScreen(
                            settings = settings,
                            onUpdateSettings = { next ->
                                scope.launch { vm.settingsRepo.save(next) }
                            },
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.Profile -> {
                        UserProfileScreen(
                            profile = userProfile,
                            onSave = { updated -> vm.updateUserProfile(updated) },
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.Backup -> {
                        BackupScreen(onBack = { popScreen() })
                    }

                    is Screen.McpManage -> {
                        McpManageScreen(
                            mcpStore = vm.mcpStore,
                            aggregateStore = vm.aggregateStore,
                            onOpenDetail = { sid -> navigateTo(Screen.McpDetail(sid)) },
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.McpDetail -> {
                        McpServerDetailScreen(
                            serverId = target.serverId,
                            mcpStore = vm.mcpStore,
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.Assistants -> {
                        AssistantsScreen(
                            assistants = assistants,
                            activeId = settings.activeAssistantId,
                            onBack = { popScreen() },
                            onSelect = { aid ->
                                vm.selectAssistant(aid)
                                popScreen()
                            },
                            onUpsert = { a -> vm.saveAssistant(a) },
                            onDelete = { aid -> vm.assistantStore.deleteDirect(aid) },
                            onEdit = { a -> navigateTo(Screen.EditAssistant(a)) }
                        )
                    }

                    is Screen.EditAssistant -> {
                        AssistantEditorScreen(
                            initial = target.assistant,
                            availableSkills = skills,
                            onSave = { a ->
                                vm.saveAssistant(a)
                                popScreen()
                            },
                            onCancel = { popScreen() }
                        )
                    }

                    is Screen.Skills -> {
                        SkillsScreen(
                            skills = skills,
                            onSetEnabled = { sid, enabled -> vm.toggleSkill(sid, enabled) },
                            onSave = { s -> scope.launch { vm.skillStore.upsert(s) } },
                            onDelete = { sid -> vm.skillStore.deleteDirect(sid) },
                            onBack = { popScreen() }
                        )
                    }

                    is Screen.About -> {
                        AboutScreen(
                            onBack = { popScreen() },
                            onOpenDebug = { navigateTo(Screen.Debug) },
                            onOpenLogPreview = { navigateTo(Screen.LogPreview) }
                        )
                    }

                    is Screen.Debug -> {
                        DebugScreen(onBack = { popScreen() })
                    }

                    is Screen.LogPreview -> {
                        LogPreviewScreen(onBack = { popScreen() })
                    }
                }
            }

            if (showModelPicker) {
                ModelPickerBottomSheet(
                    sheetState = modelPickerSheetState,
                    providers = providers,
                    activeProviderId = settings.activeProviderId,
                    activeModel = if (pickingForTitleModel) settings.titleModel
                    else settings.activeModel,
                    onSelectModel = { pid, mid ->
                        if (pickingForTitleModel) {
                            scope.launch {
                                vm.settingsRepo.update { it.copy(titleModel = mid) }
                            }
                        } else {
                            vm.setActiveModel(pid, mid)
                        }
                    },
                    onManageProviders = { navigateTo(Screen.Providers) },
                    onDismiss = { showModelPicker = false }
                )
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
