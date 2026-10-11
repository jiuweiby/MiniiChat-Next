package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.Role
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.model.ToolInvocationState
import com.miniichatNext.carter.ui.chat.input.InputBar
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.skills.Skill

@Composable
fun ChatScreen(
    conversation: Conversation?,
    settings: AppSettings,
    activeProvider: ProviderConfig?,
    isStreaming: Boolean,
    streamingOverlay: Pair<String, String>? = null,
    assistant: Assistant? = null,
    userProfile: UserProfile = UserProfile(),
    assistants: List<Assistant> = emptyList(),
    skills: List<Skill> = emptyList(),
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onContextCompress: (Int, Int, String) -> Unit = { _, _, _ -> },
    compressingProgressText: String? = null,
    onSuggestions: (Int) -> Unit = {},
    suggestionsGenerating: Set<Int> = emptySet(),
    onRenameChat: (String) -> Unit = {},
    effectiveSkillIds: Set<String> = emptySet(),
    onSelectAssistant: (String) -> Unit = {},
    /**
     * 存储尚未完成首次加载。
     *
     * 为 true 时聊天区显示加载态而不是空态。
     *
     * 必要性：ConversationStore 的缓存以空列表起步，磁盘内容要等首次访问才读入。
     * 若不加这个标志，启动瞬间 messages 必为空，界面会先渲染一次
     * 「开始新对话」欢迎页，等数据到达后再切到真实会话，产生可见闪动。
     * 用户看到的就是「先闪到历史会话，又回首页」。
     */
    initializing: Boolean = false,
    onMenu: () -> Unit,
    onSend: (String, List<Attachment>) -> Unit,
    onStop: () -> Unit,
    onRegenerateFrom: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onContinue: () -> Unit = {},
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit,
    onApproveTool: ((ToolInvocation, String?) -> Unit)? = null,
    onRejectTool: ((ToolInvocation) -> Unit)? = null
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    // 会话身份标识：切换会话 / 重启恢复时用于重置滚动状态
    val convIdentity = conversation?.id

    // 原始消息列表，不做任何逐帧复制。
    // 流式 overlay 由 MessageItem 内部按消息 id 精确匹配，
    // 避免旧实现中 "每帧 map 整个列表 + 拷贝所有 Message 对象" 造成的 O(n) 分配与全量重组
    val messages = conversation?.messages ?: emptyList()

    val listState = rememberLazyListState()

    // 滚动策略：
    //  - 首屏 / 切换会话 / 重启恢复 -> scrollToItem 瞬时定位（绝不用 animate）
    //    旧实现用 animateScrollToItem，需要逐帧测量起点到终点的所有 item，
    //    1000 条消息时会退化成每秒个位数帧，这就是「进入历史会话向下滑动」的根因
    //  - 流式过程中新增消息 -> scrollToItem 瞬时贴底，不与流式刷新抢帧
    //  - 用户主动操作后新增消息 -> animateScrollToItem 平滑滚动
    var lastCount by remember(convIdentity) { mutableStateOf(-1) }
    var lastMsgId by remember(convIdentity) { mutableStateOf<String?>(null) }
    var firstLayoutDone by remember(convIdentity) { mutableStateOf(false) }

    LaunchedEffect(convIdentity, messages.size) {
        if (messages.isEmpty()) {
            lastCount = 0
            lastMsgId = null
            firstLayoutDone = false
            return@LaunchedEffect
        }

        val newCount = messages.size
        val newLastId = messages.last().id

        if (!firstLayoutDone) {
            firstLayoutDone = true
            listState.scrollToItem(messages.lastIndex)
            lastCount = newCount
            lastMsgId = newLastId
            return@LaunchedEffect
        }

        val grew = newCount > lastCount
        lastCount = newCount
        lastMsgId = newLastId

        if (!grew) return@LaunchedEffect

        if (isStreaming) {
            listState.scrollToItem(messages.lastIndex)
        } else {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDraft by rememberSaveable { mutableStateOf("") }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var showCompressDialog by rememberSaveable { mutableStateOf(false) }

    // 两个内嵌面板：+ 面板 与 候选回复面板（互斥，点空白处统一收起）
    var addPanelOpen by rememberSaveable { mutableStateOf(false) }
    var candidatesOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionPage by rememberSaveable { mutableStateOf(0) }
    var focusInputRequest by remember { mutableStateOf(0) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val immersiveAlpha by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        animationSpec = tween(durationMillis = 320),
        label = "immersive-alpha"
    )

    val backgroundMode = assistant?.backgroundMode ?: "default"
    val backgroundPath = assistant?.backgroundPath
    val backgroundCss = assistant?.backgroundCss ?: ""
    val backgroundOpacity = assistant?.backgroundOpacity ?: 1f

    // CSS 背景 HTML 只在真正启用 css 模式时构建，避免无谓的字符串拼接
    val cssHtml = remember(backgroundMode, backgroundCss) {
        if (backgroundMode == "css" && backgroundCss.isNotBlank()) {
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<meta name=\"color-scheme\" content=\"light dark\">" +
                "<style>" +
                "html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden}" +
                backgroundCss + "\n" +
                "</style></head><body></body></html>"
        } else null
    }
    val cssFallbackColors = remember(cssHtml, backgroundCss) {
        if (cssHtml != null) parseCssFallbackColors(backgroundCss) else null
    }

    // 非 CSS 模式才解码位图，CSS 模式下跳过整次解码
    val backgroundBitmap = if (backgroundMode == "css") null
                           else rememberBackgroundBitmap(backgroundPath)

    val hasBackgroundLayer = cssHtml != null ||
        backgroundBitmap != null ||
        (backgroundMode == "image" && !backgroundPath.isNullOrBlank())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { immersive = true },
                    onTap = { immersive = false }
                )
            }
    ) {
        if (cssHtml != null) {
            cssFallbackColors?.let { colors ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (colors.size >= 2) {
                                Brush.linearGradient(colors)
                            } else {
                                androidx.compose.ui.graphics.SolidColor(colors.first())
                            }
                        )
                )
            }
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        // 旧实现在这里用 settings.javaClass 反射调用 WebSettings 方法，
                        // 但此作用域内的 settings 是 AppSettings 参数而非 WebView.settings，
                        // 所有 runCatching 都静默失败 —— JS 实际从未被禁用。
                        // 改为直接操作真正的 WebSettings。
                        runCatching {
                            val ws = this.settings
                            ws.javaScriptEnabled = false
                            ws.javaScriptCanOpenWindowsAutomatically = false
                            ws.allowFileAccess = false
                            ws.allowContentAccess = false
                            ws.domStorageEnabled = false
                            ws.databaseEnabled = false
                            ws.setSupportZoom(false)
                            ws.builtInZoomControls = false
                            ws.displayZoomControls = false
                            ws.loadsImagesAutomatically = false
                            ws.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                ws.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                            }
                        }
                        isClickable = false
                        isFocusable = false
                        isLongClickable = false
                        setOnTouchListener { _, _ -> false }
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        // 硬件层渲染，避免背景合成走软件路径反复重绘
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                // 背景 CSS 只允许内联样式生效，拒绝一切外部资源加载
                                return android.webkit.WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            override fun onReceivedError(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                val mainFrame = request?.isForMainFrame ?: true
                                if (mainFrame) {
                                    view?.visibility = android.view.View.INVISIBLE
                                }
                            }
                        }
                        tag = cssHtml
                        loadDataWithBaseURL(null, cssHtml, "text/html", "utf-8", null)
                    }
                },
                update = { wv ->
                    if (wv.tag != cssHtml) {
                        wv.tag = cssHtml
                        wv.loadDataWithBaseURL(null, cssHtml, "text/html", "utf-8", null)
                    }
                },
                onRelease = { wv ->
                    wv.stopLoading()
                    wv.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (backgroundBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = backgroundBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 1f - backgroundOpacity))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = if (hasBackgroundLayer) 0f else 1f))
                .imePadding()
                .graphicsLayer { alpha = immersiveAlpha }
        ) {
            ChatTopBar(
                title = conversation?.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.app_name),
                onMenu = onMenu,
                onEditTitle = {
                    renameDraft = conversation?.title ?: ""
                    showRenameDialog = true
                },
                onNew = onNew,
                transparent = false
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // 首次加载中：显示占位而非空态。
                    // 顺序必须在 messages.isEmpty() 之前判断，
                    // 否则数据未就绪的那一帧会先渲染欢迎页。
                    initializing -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    messages.isEmpty() -> {
                        EmptyState(
                            onPick = { onSend(it, emptyList()) },
                            onOpenSettings = onOpenSettings,
                            showSettingsHint = activeProvider == null,
                            assistantAvatar = Avatar.fromLegacy(
                                assistant?.avatar ?: "🤖", assistant?.avatarPath
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        val lastAssistantId = remember(messages) {
                            messages.lastOrNull()?.takeIf { it.role == "assistant" }?.id
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            itemsIndexed(
                                items = messages,
                                key = { _, msg -> msg.id },
                                contentType = { _, msg -> msg.role }
                            ) { index, msg ->
                                val isUserMsg = msg.role == "user"
                                MessageItem(
                                    message = msg,
                                    avatar = if (isUserMsg) userProfile.avatar
                                    else Avatar.fromLegacy(
                                        assistant?.avatar ?: "🤖", assistant?.avatarPath
                                    ),
                                    senderLabel = if (msg.role == "user")
                                        userProfile.displayName.ifBlank {
                                            stringResource(R.string.profile_default_name)
                                        }
                                    else settings.activeModel.ifBlank {
                                        activeProvider?.name ?: stringResource(R.string.assistant)
                                    },
                                    settings = settings,
                                    isLastAssistant = msg.id == lastAssistantId,
                                    isStreaming = isStreaming,
                                    streamingOverride = if (streamingOverlay?.first == msg.id)
                                        streamingOverlay.second else null,
                                    // 上一条是「只含工具调用的 assistant」时，本条不再重复渲染头像行。
                                    // 这样 AI 连续发起多轮工具调用时，视觉上是一组连续的卡片，
                                    // 而不是被一串重复的头像与模型名打断。
                                    suppressAssistantHeader = shouldSuppressAssistantHeader(
                                        messages = messages,
                                        index = index
                                    ),
                                    editing = editingMessageId == msg.id,
                                    editingDraft = if (editingMessageId == msg.id) editingDraft else "",
                                    onEditingDraftChange = { editingDraft = it },
                                    onStartEdit = {
                                        editingMessageId = msg.id
                                        editingDraft = msg.content
                                    },
                                    onCommitEdit = {
                                        editingMessageId?.let { id ->
                                            onEditMessage(id, editingDraft)
                                        }
                                        editingMessageId = null
                                        editingDraft = ""
                                    },
                                    onCancelEdit = {
                                        editingMessageId = null
                                        editingDraft = ""
                                    },
                                    onContinue = onContinue,
                                    onDelete = { onDeleteMessage(msg.id) },
                                    onRegenerateFrom = { onRegenerateFrom(msg.id) },
                                    onApproveTool = onApproveTool,
                                    onRejectTool = onRejectTool
                                )
                            }
                        }
                    }
                }

                if (addPanelOpen || candidatesOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(addPanelOpen, candidatesOpen) {
                                var fired = false
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (fired) continue
                                        if (event.changes.any {
                                                it.positionChanged() || it.changedToUp()
                                            }
                                        ) {
                                            fired = true
                                            addPanelOpen = false
                                            candidatesOpen = false
                                        }
                                    }
                                }
                            }
                    )
                }
            }

            // InputBar 的高频回调统一 remember 固定引用。
            //
            // 必要性：ChatScreen 在流式期间会因 streamingOverlay / isStreaming 变化频繁重组，
            // 若这些 lambda 每次重组都重建新实例，InputBar 的入参就永远「不相等」，
            // 无法被 Compose 跳过，导致底部输入框区（底栏）在每次流式刷新时都被重组，
            // 表现为「底栏切换/输入卡顿」。
            //
            // 安全性：lambda 内访问的是 `var x by mutableState`（委托属性）而非快照值，
            // 固定 lambda 实例后，读取/写入仍指向同一个 state 对象，行为完全不变。
            val onInputChange = remember { { v: String -> input = v } }
            val onAttachmentsChange = remember { { a: List<Attachment> -> pendingAttachments = a } }
            val onOpenCompress = remember { { showCompressDialog = true } }
            val onSendClick = remember {
                {
                    val text = input
                    val atts = pendingAttachments
                    input = ""
                    pendingAttachments = emptyList()
                    candidatesOpen = false
                    addPanelOpen = false
                    onSend(text, atts)
                }
            }
            val onToggleAdd = remember {
                {
                    val next = !addPanelOpen
                    addPanelOpen = next
                    if (next) candidatesOpen = false
                }
            }
            val onToggleCandidates = remember {
                {
                    val next = !candidatesOpen
                    candidatesOpen = next
                    if (next) {
                        addPanelOpen = false
                        suggestionPage = 0
                    }
                }
            }

            InputBar(
                value = input,
                onValueChange = onInputChange,
                attachments = pendingAttachments,
                onAttachmentsChange = onAttachmentsChange,
                modelLabel = settings.activeModel.ifBlank { stringResource(R.string.select_model) },
                onPickModel = onPickModel,
                skills = skills,
                enabledSkillIds = effectiveSkillIds,
                onToggleSkill = onToggleSkill,
                onContextCompress = onOpenCompress,
                onSend = onSendClick,
                onStop = onStop,
                isStreaming = isStreaming,
                // 首次加载期间禁用发送，避免在会话尚未确定时提交消息
                enabled = !initializing && activeProvider != null && settings.activeModel.isNotBlank(),
                settings = settings,
                transparent = false,
                addPanelOpen = addPanelOpen,
                onToggleAddPanel = onToggleAdd,
                candidatesOpen = candidatesOpen,
                onToggleCandidates = onToggleCandidates,
                candidatesPanel = {
                    SuggestionPanel(
                        conversation = conversation,
                        page = suggestionPage,
                        loading = suggestionPage in suggestionsGenerating,
                        onPageChange = { suggestionPage = it },
                        onGenerate = { onSuggestions(it) },
                        onPick = { candidate ->
                            candidatesOpen = false
                            onSend(candidate, emptyList())
                        },
                        onEdit = { candidate ->
                            input = candidate
                            candidatesOpen = false
                            addPanelOpen = false
                            focusInputRequest++
                        }
                    )
                },
                onDismissPanels = {
                    addPanelOpen = false
                    candidatesOpen = false
                },
                focusInputRequest = focusInputRequest
            )
        }

        if (showCompressDialog) {
            // 对话框内的统计信息与保留上限全部就地由当前消息列表推导，
            // 不再依赖 ViewModel 的异步状态，避免打开瞬间显示 0 导致误解。
            val maxKeep = remember(messages.size) {
                (messages.size / 3).coerceAtLeast(1)
            }
            val toolCallCount = remember(messages) {
                messages.sumOf { msg ->
                    msg.toolInvocations.count { it.state == ToolInvocationState.SUCCESS }
                }
            }

            CompressContextDialog(
                totalMessages = messages.size,
                maxKeep = maxKeep,
                toolCallCount = toolCallCount,
                onDismiss = { showCompressDialog = false },
                onConfirm = { tokens, keep, prompt ->
                    showCompressDialog = false
                    onContextCompress(tokens, keep, prompt)
                }
            )
        }

        if (compressingProgressText != null) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.context_compress)) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = compressingProgressText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {}
            )
        }

        if (immersive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures { immersive = false }
                    }
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onRenameChat(renameDraft.ifBlank { "" })
                    showRenameDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 判断某条消息是否需要抑制头像行。
 *
 * 规则：当前消息是 assistant，且上一条**有效消息**（跳过 tool 角色）也满足
 * 「assistant 且只有工具调用、没有正文、没有思考内容」时，抑制本条的头部。
 *
 * 为什么要求上一条也必须「只有工具调用」而不是任意 assistant：
 *   如果上一条是带正文的完整回答，本条再起一张工具卡片时属于新一轮交互，
 *   此时保留头像行有助于用户区分「上一轮回答」与「新一轮工具调用」。
 *   只有当上下两条都是纯粹的工具调用流转时，重复头像才真的多余。
 */
private fun shouldSuppressAssistantHeader(
    messages: List<Message>,
    index: Int
): Boolean {
    val current = messages.getOrNull(index) ?: return false
    if (current.role != Role.ASSISTANT) return false

    // 向上找到第一条有效的前置消息（跳过 tool 角色）
    var i = index - 1
    while (i >= 0) {
        val prev = messages[i]
        if (prev.role == Role.TOOL) {
            i--
            continue
        }
        return prev.role == Role.ASSISTANT &&
            prev.toolInvocations.isNotEmpty() &&
            prev.content.isBlank() &&
            prev.reasoningContent.isNullOrBlank()
    }
    return false
}
