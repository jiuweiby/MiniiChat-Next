package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.ui.components.AvatarView
import com.miniichatNext.carter.ui.markdown.MarkdownText

/** 思考过程折叠态最多显示的字符数，超出部分引导用户跳全文页 */
private const val REASONING_PREVIEW_CHARS = 4000

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AssistantRow(
    message: Message,
    avatar: Avatar,
    senderLabel: String,
    settings: AppSettings,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    /**
     * 上一条消息是否也是 assistant 且只包含工具调用（没有正文、没有思考内容）。
     *
     * 用于实现「连续工具调用卡片只渲染一次头像与模型名」：
     * 当 AI 连续发起多轮工具调用时，每个 assistant 消息会产生一张卡片，
     * 若每条都重复渲染头像行，视觉上会形成一串无意义的重复标识。
     */
    suppressHeader: Boolean = false,
    editing: Boolean = false,
    editingDraft: String = "",
    onEditingDraftChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onContinue: () -> Unit = {},
    onDelete: () -> Unit,
    onApproveTool: ((ToolInvocation, String?) -> Unit)? = null,
    onRejectTool: ((ToolInvocation) -> Unit)? = null
) {
    // 始终显示工具卡片（若消息有工具调用，不管是否开启设置，避免闪现后消失）
    val hasToolInvocations = message.toolInvocations.isNotEmpty()
    val hasReasoningVisible = settings.showReasoningCard &&
        !message.reasoningContent.isNullOrBlank()
    val hasContent = message.content.isNotEmpty()

    if (!hasContent && !hasReasoningVisible && !hasToolInvocations &&
        (!isLastAssistant || !isStreaming)
    ) {
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    // 思考全文查看状态
    var reasoningFullText by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    reasoningFullText?.let { content ->
        FullTextScreen(
            title = "深度思考过程",
            text = content,
            onBack = { reasoningFullText = null }
        )
        return
    }

    val finalSenderLabel = remember(message.model, message.providerName, senderLabel) {
        if (!message.model.isNullOrBlank()) {
            val pName = message.providerName?.ifBlank { null }
            if (pName != null) "$pName · ${message.model}" else message.model!!
        } else {
            senderLabel
        }
    }

    // 提取模型标识符（用于自动在 assets/icons 中查找官方品牌 SVG）
    val modelKey = remember(message.model, message.providerName) {
        listOfNotNull(message.model, message.providerName).joinToString(" ")
    }

    // 是否真的需要渲染头像行：
    //   suppressHeader 为真时说明上一条就是工具调用，当前卡片直接顶上去，不重复标识。
    val showHeader = !suppressHeader

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarView(
                    avatar = avatar,
                    fallbackInitial = "M",
                    size = 22.dp,
                    modelIdentifier = modelKey
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    finalSenderLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // 1. 深度思考推理折叠卡片
        if (hasReasoningVisible) {
            val reasoning = message.reasoningContent.orEmpty()
            val reasoningTooLong = reasoning.length > REASONING_PREVIEW_CHARS

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { reasoningExpanded = !reasoningExpanded },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isStreaming && isLastAssistant && message.content.isBlank())
                                "正在深度思考…" else "深度思考过程",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (reasoningTooLong) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatCount(reasoning.length),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = if (reasoningExpanded)
                                Icons.Default.KeyboardArrowUp
                            else
                                Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = reasoningExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            // 关键：思考内容可能几十 KB，直接全量塞进 Text 会因
                            // LazyColumn 项内无约束高度而全量测量，从而卡死甚至 OOM。
                            // 这里做两件事：
                            //  1. 视觉高度加上限，逼长文本走内部滚动而非无限撑高
                            //  2. 超长时只渲染前 N 字符，尾巴引导到全文页
                            val preview = remember(reasoning) {
                                if (reasoning.length > REASONING_PREVIEW_CHARS) {
                                    reasoning.substring(0, REASONING_PREVIEW_CHARS)
                                } else {
                                    reasoning
                                }
                            }
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = (settings.fontSizeSp - 2f)
                                        .coerceAtLeast(11f).sp,
                                    lineHeight = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            )

                            if (reasoningTooLong) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "仅显示前 $REASONING_PREVIEW_CHARS 字符",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { reasoningFullText = reasoning }) {
                                        Text("查看全文", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 工具调用卡片列表
        if (hasToolInvocations) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.toolInvocations.forEach { inv ->
                    // 先把可空回调收进非空局部变量，避免在 lambda 内触发智能转换限制。
                    val approve = onApproveTool
                    val reject = onRejectTool
                    ToolInvocationCard(
                        invocation = inv,
                        onApprove = if (inv.state == "pending_approval" && approve != null) {
                            // 关键：把澄清卡片提交的 payload 原样透传给上层，
                            // 不能再写成 { onApproveTool(inv) } —— 那会丢弃用户的选择，
                            // 导致 approveToolCall 拿不到 customResult，
                            // 转而执行 interactiveClarification(args) 生成「原始选项清单」作为结果，
                            // 最终 AI 收到的是它自己当初列的选项，而非用户真正选中的那一项。
                            { payload -> approve(inv, payload) }
                        } else null,
                        onReject = if (inv.state == "pending_approval" && reject != null) {
                            { reject(inv) }
                        } else null
                    )
                }
            }
        }

        // 3. AI 消息气泡
        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = editingDraft,
                    onValueChange = onEditingDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = settings.fontSizeSp.sp,
                        lineHeight = (settings.fontSizeSp * 1.4f).sp
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
        } else if (message.content.isEmpty() && isLastAssistant && isStreaming &&
            message.toolInvocations.isEmpty()
        ) {
            TypingDots()
        } else if (message.content.isNotEmpty()) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                topStart = 4.dp,
                                topEnd = 18.dp,
                                bottomStart = 18.dp,
                                bottomEnd = 18.dp
                            )
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (message.content.isNotEmpty()) menuOpen = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        // MarkdownText 内部按块渲染，块级 Text 各自测量，
                        // 不会像单个巨型 Text 那样一次性吃满内存
                        MarkdownText(
                            text = message.content,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                            clipboard.setText(AnnotatedString(message.content))
                            menuOpen = false
                        }
                    )
                    if (isLastAssistant) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onStartEdit()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.continue_generating)) },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = {
                                menuOpen = false
                                onContinue()
                            }
                        )
                    }
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

        // 4. 卡片 Token 消耗与生成速度统计条
        if (settings.showCardTokenUsage && message.usage != null && message.content.isNotEmpty()) {
            val u = message.usage
            val statText = remember(u) {
                val durationSec = "%.1f".format(u.durationMs / 1000f)
                val speedText = "%.1f".format(u.tokensPerSecond)
                "↑ ${u.promptTokens} tokens   ↓ ${u.completionTokens} tokens   " +
                    "速度 ${speedText} tokens/s   耗时 ${durationSec}s"
            }
            Text(
                text = statText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        // 助手底部动作条
        if (message.content.isNotEmpty() && !editing && !isStreaming) {
            Spacer(Modifier.height(6.dp))
            AssistantActionBar(
                onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                onContinue = if (isLastAssistant) onContinue else null,
                onEdit = if (isLastAssistant) onStartEdit else null
            )
        }
    }
}

@Composable
internal fun AssistantActionBar(
    onCopy: () -> Unit,
    onContinue: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(Icons.Default.ContentCopy, stringResource(R.string.copy), onCopy)
        if (onContinue != null) {
            ActionChip(
                Icons.Default.PlayArrow,
                stringResource(R.string.continue_generating),
                onContinue
            )
        }
        if (onEdit != null) {
            ActionChip(Icons.Default.Edit, stringResource(R.string.edit), onEdit)
        }
    }
}

@Composable
internal fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 打字指示器
 *
 * 动画周期从 700ms 放宽到 1000ms：
 * 无限动画会持续驱动每一帧的重组与绘制，周期越短合成器压力越大。
 */
@Composable
internal fun TypingDots() {
    val infinite = rememberInfiniteTransition(label = "typing")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = when (i) {
                                0 -> alpha
                                1 -> 1f - alpha
                                else -> alpha * 0.7f + 0.3f
                            }
                        )
                    )
            )
        }
    }
}
