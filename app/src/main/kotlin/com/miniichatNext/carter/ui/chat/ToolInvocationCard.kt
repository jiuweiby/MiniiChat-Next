package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.model.ToolInvocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 调用工具的紧凑卡片组件
 *
 * 特性：
 * 1. interactive_clarification：卡片内直接渲染交互式题目与选项。
 * 2. 传统文件 / MCP 工具：显示执行状态，点击展开详情 ModalBottomSheet。
 *
 * 大文本安全策略：
 *   参数与结果可能达到数 MB（file_write 的 content、file_read 的正文、dex 反编译输出等）。
 *   所有展示这些内容的位置统一走 [SafeTextBox]：
 *     - 预览区只渲染固定长度片段，测量量恒定
 *     - 超出部分提供「查看全文」，跳转 [FullTextScreen] 按行惰性渲染
 *
 * ## 全文页为什么提升到本组件渲染
 *
 * 曾经把 FullTextScreen 直接写在 ToolDetailSheet 内部，
 * 用 `fullText?.let { FullTextScreen(...); return }` 提前返回。
 *
 * 这个写法在「先打开详情页、再点查看全文」的路径下会崩溃：
 *
 *   ToolDetailSheet 已经组合完成并挂在树上，此时 fullText 从 null 变为非空，
 *   如果还在 ToolDetailSheet 的作用域里渲染 FullTextScreen，
 *   它内部的 Scaffold + LazyColumn 就会拿到来自外层 ModalBottomSheet
 *   与 verticalScroll 的无限高度约束，触发
 *   「Vertically scrollable component was measured with an infinity
 *     maximum height constraints」直接崩掉。
 *
 * 现在改为：全文状态由本组件持有，
 * 全文页在 ModalBottomSheet 之外的兄弟层级渲染，两者互不嵌套。
 */
@Composable
fun ToolInvocationCard(
    invocation: ToolInvocation,
    onApprove: ((String?) -> Unit)? = null,
    onReject: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (invocation.toolName == "interactive_clarification") {
        InteractiveClarificationCard(
            invocation = invocation,
            onApprove = onApprove,
            modifier = modifier
        )
        return
    }

    var showDetailSheet by remember { mutableStateOf(false) }

    // 全文查看状态：提升到此处持有，保证全文页与详情页处于兄弟层级
    var fullText by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ---------- 全文页优先渲染，并完全遮蔽卡片本体 ----------
    val pendingFullText = fullText
    if (pendingFullText != null) {
        FullTextScreen(
            title = pendingFullText.first,
            text = pendingFullText.second,
            onBack = { fullText = null }
        )
        return
    }

    val isPending = invocation.state == "pending_approval"
    val isRunning = invocation.state == "executing" || invocation.state == "running"
    val isSuccess = invocation.state == "success"
    val isError = invocation.state == "error"
    val isRejected = invocation.state == "rejected"

    val statusColor = when {
        isPending -> MaterialTheme.colorScheme.tertiary
        isRunning -> MaterialTheme.colorScheme.primary
        isSuccess -> Color(0xFF2E7D32)
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    val statusText = when {
        isPending -> "等待审批"
        isRunning -> "正在执行…"
        isSuccess -> "调用完成"
        isError -> "调用出错"
        isRejected -> "已拒绝"
        else -> invocation.state
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { showDetailSheet = true },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            statusColor.copy(alpha = if (isRunning || isPending) 0.65f else 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ToolStatusLeading(
                    isRunning = isRunning,
                    isPending = isPending,
                    isSuccess = isSuccess,
                    isError = isError,
                    statusColor = statusColor
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = invocation.toolName.ifBlank { "未知工具" },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· $statusText",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            fontSize = 11.sp
                        )
                    }

                    val previewText = remember(invocation.arguments) {
                        singleLinePreview(invocation.arguments)
                    }
                    if (previewText.isNotBlank()) {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = "查看详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }

            if (isPending && (onApprove != null || onReject != null)) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onReject != null) {
                        OutlinedButton(
                            onClick = onReject,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(
                                horizontal = 12.dp,
                                vertical = 0.dp
                            )
                        ) {
                            Text("拒绝", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (onApprove != null) {
                        Button(
                            onClick = { onApprove(null) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(
                                horizontal = 14.dp,
                                vertical = 0.dp
                            )
                        ) {
                            Text("同意调用", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showDetailSheet) {
        ToolDetailSheet(
            invocation = invocation,
            onDismiss = { showDetailSheet = false },
            onOpenFullText = { title, content -> fullText = title to content },
            onApprove = if (onApprove != null) { { onApprove(null) } } else null,
            onReject = onReject
        )
    }
}

/**
 * 卡片左侧的状态图标。
 *
 * 独立成组件是为了让主 Row 保持浅嵌套：
 * 原先这一段是 4 个并列的 if-else 分支，把整行代码推向屏幕右边缘，
 * 排查括号配对时非常费劲。
 */
@Composable
private fun ToolStatusLeading(
    isRunning: Boolean,
    isPending: Boolean,
    isSuccess: Boolean,
    isError: Boolean,
    statusColor: Color
) {
    when {
        isRunning -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.4.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        )

        isPending -> Box(
            modifier = Modifier
                .size(24.dp)
                .background(statusColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(14.dp)
            )
        }

        isSuccess -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(22.dp)
        )

        isError -> Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(22.dp)
        )

        else -> Icon(
            Icons.Default.Close,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 把澄清卡片的回传结果（结构化 JSON）转成人类可读文案。
 *
 * 存入 [ToolInvocation.result] 的内容形如：
 *   {"selected":["方案二"],"count":1,"allow_multiple":false,"custom_input":"..."}
 *
 * 历史恢复时卡片需要把它显示出来，直接展示原始 JSON 很丑，
 * 这里解析成「已确认选项：方案二；补充说明：…」。
 *
 * 解析失败时原样返回，保证不会因为脏数据把界面搞崩。
 */
private fun formatClarificationResult(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return raw
    return runCatching {
        val obj = Json.parseToJsonElement(trimmed).jsonObject
        val selected = obj["selected"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val custom = obj["custom_input"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        buildString {
            append("已提交选择：")
            append(if (selected.isNotEmpty()) selected.joinToString("、") else "未选择预设选项")
            if (custom.isNotBlank()) {
                append("；补充说明：").append(custom)
            }
        }
    }.getOrElse { raw }
}

/**
 * 需求理解与澄清交互式选择卡片。
 *
 * 关于「选了方案 2 却回传方案 3」的修复要点（按修复时间排序，最后一条才是真根因）：
 *
 *  1. 单选状态不再用 options.firstOrNull() 预置，而是显式保存「用户点选的那一项文本」。
 *     旧实现预选了第一项，一旦组件因流式刷新被重建，remember 会被重置回预选值，
 *     此时用户看到的高亮还在第 2 项（因为是点击后立即重组），但内部状态已回到第 1 项。
 *
 *  2. 用 invocation.callId 作为 remember 的 key。
 *     同一个会话里可能先后出现多次澄清请求，必须保证换了一个澄清请求就重置选择状态，
 *     而同一请求在状态流转（pending -> executing -> success）时不重置。
 *
 *  3. 提交后立刻把 submitted 置真并写入最终回显文本。
 *     旧实现依赖 invocation.result 反推是否已提交，但 result 是在服务端往返之后才写入的，
 *     中间有一段时间窗口允许重复点击，第二次点击携带的是被重置的默认选项。
 *
 *  4. 回传内容使用「结构化 JSON」而非自然语言。
 *     自然语言里同时出现「已确认选项：方案一、方案二」和「自定义补充」时，
 *     模型容易只抓取其中的第一项。改为显式字段后，模型必须逐字段读取。
 *
 *  5. 【真根因】用户的选择曾在上层被整体丢弃。
 *     本卡片通过 onApprove(payload) 把用户选择交出去，但上层
 *     AssistantRow 曾写成 `onApprove = { onApproveTool(inv) }`，
 *     这个 lambda 的形参是 String?，却完全没使用它，只把 inv 传下去。
 *     结果 approveToolCall 收到的 customResult 永远是 null，
 *     于是回退到执行 interactiveClarification(args)，
 *     生成的是「已向用户展示选项卡片…选项1/2/3…」——即模型的原始选项清单，
 *     与用户实际点选无关，模型自然读成了别的方案。
 *     修复：onApproveTool 的类型改为 (ToolInvocation, String?) -> Unit，
 *     把 payload 一路透传到 approveToolCall 的 customResult。
 */
@Composable
private fun InteractiveClarificationCard(
    invocation: ToolInvocation,
    onApprove: ((String?) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val json = remember { Json { ignoreUnknownKeys = true } }

    val parsedData = remember(invocation.arguments) {
        runCatching {
            val obj = json.parseToJsonElement(invocation.arguments).jsonObject
            val q = obj["question"]?.jsonPrimitive?.contentOrNull ?: "请确认您的需求选项"
            val rawOpts = obj["options"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val allowMulti = obj["allow_multiple"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            Triple(q, rawOpts.take(4), allowMulti)
        }.getOrDefault(Triple("请选择项目需求方案", listOf("默认实现方案"), false))
    }

    val question = parsedData.first
    val options = parsedData.second
    val allowMultiple = parsedData.third

    // 以 callId 为 key：换一个澄清请求就重置，同一请求的状态流转不重置
    val stateKey = invocation.callId.ifBlank { invocation.toolName + question }

    // 单选：不预选任何项，强制用户显式点选，避免「默认值被当成用户选择」提交上去
    var singleSelected by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    // 多选：用不可序列化的 list 承载，但同样按 callId 隔离
    val multiSelected = remember(stateKey) { mutableStateListOf<String>() }
    var customInput by rememberSaveable(stateKey) { mutableStateOf("") }
    // 提交锁：一旦提交就不再允许改动与二次提交
    var submitted by rememberSaveable(stateKey) { mutableStateOf(false) }
    // 提交后回显文案：本地立即写入，不等服务端 result 回填
    var submittedEcho by rememberSaveable(stateKey) { mutableStateOf("") }

    // 服务端已有结果（例如从历史记录恢复）时同样视为已提交
    val alreadyResolved = invocation.result.isNotBlank()
    val locked = submitted || alreadyResolved

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = androidx.compose.foundation.BorderStroke(
            1.2.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---------- 标题 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (allowMultiple) {
                    "提示：支持多选，可在末尾手动输入自定义需求"
                } else {
                    "提示：请选择一个选项，可在末尾手动输入自定义需求"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // ---------- 选项列表 ----------
            options.forEachIndexed { index, optionText ->
                val selected = if (allowMultiple) {
                    optionText in multiSelected
                } else {
                    singleSelected == optionText
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable(enabled = !locked) {
                            if (allowMultiple) {
                                if (optionText in multiSelected) {
                                    multiSelected.remove(optionText)
                                } else {
                                    multiSelected.add(optionText)
                                }
                            } else {
                                singleSelected = optionText
                            }
                        }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (allowMultiple) {
                        Checkbox(
                            checked = selected,
                            enabled = !locked,
                            onCheckedChange = { chk ->
                                if (chk) {
                                    if (optionText !in multiSelected) {
                                        multiSelected.add(optionText)
                                    }
                                } else {
                                    multiSelected.remove(optionText)
                                }
                            }
                        )
                    } else {
                        RadioButton(
                            selected = selected,
                            enabled = !locked,
                            onClick = { singleSelected = optionText }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "选项 ${index + 1}: $optionText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ---------- 自定义补充 ----------
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = customInput,
                onValueChange = { if (!locked) customInput = it },
                label = { Text("补充说明（可选，会与所选选项一同提交）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !locked
            )

            Spacer(Modifier.height(14.dp))

            // ---------- 提交 / 已提交回显 ----------
            if (!locked) {
                val canSubmit = if (allowMultiple) {
                    multiSelected.isNotEmpty() || customInput.isNotBlank()
                } else {
                    singleSelected != null || customInput.isNotBlank()
                }

                Button(
                    enabled = canSubmit && onApprove != null,
                    onClick = {
                        // 先锁状态，再做任何事，彻底杜绝重复提交
                        submitted = true

                        val chosen: List<String> = if (allowMultiple) {
                            multiSelected.toList()
                        } else {
                            listOfNotNull(singleSelected)
                        }
                        val extra = customInput.trim()

                        // 结构化回传：显式字段，避免模型只抓取到第一项
                        val payload = buildString {
                            append('{')
                            append("\"selected\":[")
                            append(chosen.joinToString(",") { "\"${it.escapeJson()}\"" })
                            append(']')
                            append(",\"count\":").append(chosen.size)
                            append(",\"allow_multiple\":").append(allowMultiple)
                            if (extra.isNotBlank()) {
                                append(",\"custom_input\":\"").append(extra.escapeJson()).append('"')
                            }
                            append('}')
                        }

                        submittedEcho = buildString {
                            append("已提交选择：")
                            append(
                                if (chosen.isNotEmpty()) chosen.joinToString("、")
                                else "未选择预设选项"
                            )
                            if (extra.isNotBlank()) {
                                append("；补充说明：").append(extra)
                            }
                        }

                        onApprove?.invoke(payload)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("提交选择并继续", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            if (submittedEcho.isNotBlank()) {
                                append(submittedEcho)
                            } else {
                                // 本地没有提交记录，说明是从历史恢复的。
                                // 这里存的是结构化 JSON（{"selected":[...],"custom_input":"..."}），
                                // 直接原样展示会很难看，解析成人类可读文案再显示。
                                append(formatClarificationResult(invocation.result))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * JSON 字符串转义。
 *
 * 用户自定义补充内容完全可能包含引号、反斜杠、换行，
 * 不做转义会直接生成非法 JSON，导致下游解析失败。
 */
private fun String.escapeJson(): String {
    val sb = StringBuilder(length + 8)
    this.forEach { c ->
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

/**
 * 工具执行详情底部弹窗。
 *
 * ## 高度约束的处理
 *
 * ModalBottomSheet 的内容高度按「最大不超过屏幕的一部分」约束，
 * 因此可以安全地使用 verticalScroll。
 *
 * 但**绝不能**在 verticalScroll 内部再嵌套 LazyColumn：
 * verticalScroll 会向子项传递无限高度约束，LazyColumn 收到后直接抛
 * IllegalStateException 崩溃。
 *
 * 因此全文查看页 [FullTextScreen] 不在这里渲染，
 * 而是通过 [onOpenFullText] 回调交给上层，在弹窗之外的兄弟层级展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(
    invocation: ToolInvocation,
    onDismiss: () -> Unit,
    onOpenFullText: (title: String, content: String) -> Unit,
    onApprove: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPending = invocation.state == "pending_approval"
    val isRunning = invocation.state == "executing" || invocation.state == "running"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ToolDetailHeader(
                invocation = invocation,
                isRunning = isRunning,
                onCopy = {
                    copyToClipboard(context, invocation.result)
                    android.widget.Toast.makeText(
                        context, "已复制结果", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )

            Spacer(Modifier.height(16.dp))

            // ---------- 输入参数 ----------
            SectionLabel("输入参数 (Arguments)")
            Spacer(Modifier.height(6.dp))
            val argsText = if (invocation.arguments.isBlank()) {
                if (isRunning) "（正在准备参数…）" else "（无参数）"
            } else {
                invocation.arguments
            }
            SafeTextBox(
                text = argsText,
                onOpenFull = { text -> onOpenFullText("输入参数", text) },
                minHeightDp = 40
            )

            Spacer(Modifier.height(16.dp))

            // ---------- 执行结果 ----------
            SectionLabel("执行结果 (Result)")
            Spacer(Modifier.height(6.dp))

            ToolResultBlock(
                invocation = invocation,
                isRunning = isRunning,
                isPending = isPending,
                onOpenFullText = onOpenFullText
            )

            if (isPending && (onApprove != null || onReject != null)) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onReject != null) {
                        OutlinedButton(
                            onClick = {
                                onReject()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("拒绝执行")
                        }
                    }
                    if (onApprove != null) {
                        Button(
                            onClick = {
                                onApprove()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("同意并执行")
                        }
                    }
                }
            }
        }
    }
}

/** 详情页顶部：工具名、Call ID、复制按钮 */
@Composable
private fun ToolDetailHeader(
    invocation: ToolInvocation,
    isRunning: Boolean,
    onCopy: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = invocation.toolName.ifBlank { "未命名工具" },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Text(
                text = "Call ID: ${invocation.callId.ifBlank { "local" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }

        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        IconButton(onClick = onCopy) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "复制结果",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 详情页的分组标题 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary
    )
}

/** 执行结果区块：运行中 / 有错误 / 正常三种形态 */
@Composable
private fun ToolResultBlock(
    invocation: ToolInvocation,
    isRunning: Boolean,
    isPending: Boolean,
    onOpenFullText: (title: String, content: String) -> Unit
) {
    when {
        isRunning -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在执行工具中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        invocation.error != null -> {
            SafeTextBox(
                text = "错误: ${invocation.error}",
                onOpenFull = { text -> onOpenFullText("错误信息", text) },
                minHeightDp = 40,
                contentColor = MaterialTheme.colorScheme.error
            )
        }

        else -> {
            val resultText = invocation.result.ifBlank {
                if (isPending) "（等待用户授权中…）" else "（无返回结果）"
            }
            SafeTextBox(
                text = resultText,
                onOpenFull = { text -> onOpenFullText("执行结果", text) },
                minHeightDp = 60
            )
        }
    }
}
