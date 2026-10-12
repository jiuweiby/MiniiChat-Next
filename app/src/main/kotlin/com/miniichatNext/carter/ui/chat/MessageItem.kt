package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.Role
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.ui.components.AvatarView

/** 用户气泡内联展示的最大字符数，超出引导到全文页 */
private const val USER_BUBBLE_PREVIEW_CHARS = 3000

@Composable
internal fun MessageItem(
    message: Message,
    avatar: Avatar,
    senderLabel: String,
    settings: AppSettings,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    streamingOverride: String? = null,
    /** 上一条消息是否也是只含工具调用的 assistant，用于抑制重复头像行 */
    suppressAssistantHeader: Boolean = false,
    editing: Boolean = false,
    editingDraft: String = "",
    onEditingDraftChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onContinue: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerateFrom: () -> Unit = {},
    onApproveTool: ((ToolInvocation, String?) -> Unit)? = null,
    onRejectTool: ((ToolInvocation) -> Unit)? = null
) {
    // 彻底忽略并隐藏历史残留或独立存在的 tool 角色消息
    // （工具执行结果已内嵌在 assistant 的 toolInvocations 中）
    if (message.role == Role.TOOL || message.role == "tool") {
        return
    }

    // 流式覆盖：只对命中 id 的那一条消息重建对象，
    // 其余 item 直接透传原始引用，保证 LazyColumn 里的其余项因 equals 相同而跳过重组
    val effective = if (streamingOverride != null && message.role != Role.USER) {
        remember(message, streamingOverride) { message.copy(content = streamingOverride) }
    } else {
        message
    }

    val isUser = effective.role == Role.USER || effective.role == "user"
    if (isUser) {
        UserBubble(
            message = effective,
            avatar = avatar,
            settings = settings,
            editing = editing,
            editingDraft = editingDraft,
            onEditingDraftChange = onEditingDraftChange,
            onStartEdit = onStartEdit,
            onCommitEdit = onCommitEdit,
            onCancelEdit = onCancelEdit,
            onDelete = onDelete,
            onRegenerateFrom = onRegenerateFrom
        )
    } else {
        AssistantRow(
            message = effective,
            avatar = avatar,
            senderLabel = senderLabel,
            settings = settings,
            isLastAssistant = isLastAssistant,
            isStreaming = isStreaming,
            suppressHeader = suppressAssistantHeader,
            editing = editing,
            editingDraft = editingDraft,
            onEditingDraftChange = onEditingDraftChange,
            onStartEdit = onStartEdit,
            onCommitEdit = onCommitEdit,
            onCancelEdit = onCancelEdit,
            onContinue = onContinue,
            onDelete = onDelete,
            onApproveTool = onApproveTool,
            onRejectTool = onRejectTool
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(
    message: Message,
    avatar: Avatar,
    settings: AppSettings,
    editing: Boolean,
    editingDraft: String,
    onEditingDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCommitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 用户消息全文查看：粘贴超长代码/日志时，气泡内只渲染截断片段
    var fullText by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fullText?.let { content ->
        FullTextScreen(
            title = "我发送的内容",
            text = content,
            onBack = { fullText = null }
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = Alignment.End
        ) {
            if (message.attachments.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    message.attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (att.type == "image")
                                    Icons.Default.Image
                                else
                                    Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                att.name,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                maxLines = 1
                            )
                        }
                    }
                }
                if (message.content.isNotEmpty() || editing) Spacer(Modifier.height(4.dp))
            }

            if (editing) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = editingDraft,
                        onValueChange = onEditingDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = (settings.fontSizeSp * 1.38f).sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onCancelEdit) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onCommitEdit) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            } else if (message.content.isNotEmpty()) {
                val content = message.content
                val tooLong = content.length > USER_BUBBLE_PREVIEW_CHARS
                // 预览片段只切一次，避免每次重组都做 substring 分配
                val preview = remember(content) {
                    if (content.length > USER_BUBBLE_PREVIEW_CHARS) {
                        content.substring(0, USER_BUBBLE_PREVIEW_CHARS)
                    } else {
                        content
                    }
                }

                Box {
                    Box(
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = 18.dp,
                                    bottomEnd = 4.dp
                                )
                            )
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column {
                            SelectionContainer {
                                // 无约束高度下，单个巨型 Text 会一次测量整段文本。
                                // 这里限定只渲染预览片段，测量量恒定。
                                Text(
                                    text = preview,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = settings.fontSizeSp.sp,
                                        lineHeight = (settings.fontSizeSp * 1.38f).sp
                                    )
                                )
                            }
                            if (tooLong) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "已截断 · 共 ${formatCount(content.length)} 字符",
                                        color = MaterialTheme.colorScheme.onPrimary.copy(
                                            alpha = 0.75f
                                        ),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { fullText = content }) {
                                        Text(
                                            "查看全文",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                clipboard.setText(AnnotatedString(content))
                                menuOpen = false
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onStartEdit()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.regenerate_from_here)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = {
                                menuOpen = false
                                onRegenerateFrom()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        AvatarView(
            avatar = avatar,
            fallbackInitial = "U",
            size = 22.dp
        )
    }
}
