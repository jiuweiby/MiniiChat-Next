package com.miniichatNext.carter.vm

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.ToolCall
import com.miniichatNext.carter.api.ToolSpec
import com.miniichatNext.carter.data.cache.CompressionResult
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.mcp.McpClient
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.ToolNameResolver
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.Role
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.model.ToolInvocationState
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 流式写盘最小间隔：约 4 次/秒。
 *
 * 旧实现对每个 SSE delta 都执行一次 store.snapshot() + store.save(整个会话列表)，
 * 模型每秒可吐出数十个 delta，等于每秒数十次全量序列化落盘，
 * 同时 conversationsFlow 每次发射都会触发 UI 整树重组。
 * 节流到 4 次/秒后，磁盘压力与重组次数下降一个数量级，而最终内容仍然完整。
 */
private const val FLUSH_INTERVAL_MS = 250L

private const val TAG_TOOL = "ToolRoute"

/**
 * 未执行工具调用时，回填给模型的占位说明。
 *
 * 必须存在的原因见 [buildToolResultText]。
 */
private const val PLACEHOLDER_REJECTED = "用户拒绝执行此工具调用，未产生任何输出。"
private const val PLACEHOLDER_PENDING = "此工具调用尚未获得用户审批，暂无执行结果。"
private const val PLACEHOLDER_EMPTY = "此工具调用已执行，但未返回任何内容。"

/**
 * 配对检查的输出上限。
 *
 * 会话很长时逐条打印会淹没日志，这里只保留最后若干条 ——
 * 出问题的几乎总是最近几轮，老消息的配对早已定型。
 */
private const val PAIRING_DUMP_LIMIT = 24

// ---------------------------------------------------------------------------
// 本轮工具调用批次
// ---------------------------------------------------------------------------

/**
 * 一次工具调用批次的处理进度。
 *
 * 多个工具调用可能被并行处理（自动放行的会各自启动协程），
 * 因此所有读写都在 [lock] 保护下进行，并且「判定是否完成」与「标记开始下一轮」
 * 必须是同一个临界区内的原子操作，否则会出现两个协程都判定未完成、
 * 或都判定完成导致重复触发的竞态。
 */
private class ToolBatch(val conversationId: String) {
    val lock = Mutex()

    /**
     * 本轮尚未有结果的等待项。
     *
     * 存的是 [waitingKeyOf] 计算出的 key，
     * 而不是裸的 callId —— 见该函数的说明。
     */
    val waiting = linkedSetOf<String>()

    /** 是否已经触发过下一轮生成 */
    var advanced = false
}

/**
 * 计算一笔工具调用在「等待集合」里的键。
 *
 * ## 为什么不能直接用 callId
 *
 * 部分网关返回的工具调用不带 id（callId 为空串）。
 * 如果把空串直接放进等待集合，会出两个问题：
 *
 *   1. 同一轮里的多笔无 id 调用会互相覆盖，集合里只剩一个空串
 *   2. [markToolResolved] 拿到空串去 remove("")，什么也移不掉，
 *      集合永远非空，导致**本轮结束后不会自动续接**
 *
 * 因此统一用「callId 非空则用它，否则退化为工具名」作为键。
 * 调用方（登记与移除）必须使用同一个函数，否则两侧对不上。
 */
private fun waitingKeyOf(inv: ToolInvocation): String =
    inv.callId.trim().ifBlank { inv.toolName }

/**
 * 当前活跃的工具调用批次。
 *
 * 同一时刻只可能有一个批次处于活跃状态：新一轮生成开始时会替换掉旧批次。
 */
@Volatile
private var activeBatch: ToolBatch? = null

fun ChatViewModel.send(text: String, attachments: List<Attachment> = emptyList()) {
    viewModelScope.launch {
        var cid = _activeId.value
        if (cid == null) {
            val existing = store.snapshot()
            if (existing.isNotEmpty()) {
                cid = existing.first().id
                _activeId.value = cid
            } else {
                val newConv = Conversation(
                    title = "新会话",
                    assistantId = settings.value.activeAssistantId.ifBlank { "default" }
                )
                val all = mutableListOf(newConv)
                store.save(all)
                cid = newConv.id
                _activeId.value = cid
            }
        }
        val targetCid = cid ?: return@launch

        // 用户主动发起新消息，作废上一轮遗留的批次进度
        activeBatch = null

        val userMsg = Message(
            role = Role.USER,
            content = text,
            attachments = attachments
        )
        appendMessage(targetCid, userMsg)
        triggerGeneration(targetCid)
    }
}

fun ChatViewModel.regenerate(msgId: String) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val conv = store.snapshot().find { it.id == cid } ?: return@launch
        val idx = conv.messages.indexOfFirst { it.id == msgId }
        if (idx >= 0) {
            val targetMsg = conv.messages[idx]
            val trimmed = if (targetMsg.role == Role.USER) {
                conv.messages.take(idx + 1)
            } else {
                conv.messages.take(idx)
            }
            store.save(store.snapshot().map {
                if (it.id == cid) it.copy(messages = trimmed) else it
            })
            activeBatch = null
            triggerGeneration(cid)
        }
    }
}

fun ChatViewModel.stopGeneration() {
    streamJob?.cancel()
    streamJob = null
    _isStreaming.value = false
    _streamingOverlay.value = null
    // 用户主动停止：作废批次，后续零散的工具结果不再自动续接
    activeBatch = null
}

/**
 * 批准一次工具调用。
 *
 * 执行完成后调用 [markToolResolved] 汇报进度，
 * 由它统一决定是否开启下一轮 —— 而不是每个协程各自判断。
 */
fun ChatViewModel.approveToolCall(inv: ToolInvocation, customResult: String? = null) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        updateToolInvocationStateByKey(cid, waitingKeyOf(inv), ToolInvocationState.RUNNING)
        val result = if (!customResult.isNullOrBlank()) {
            Pair(true, customResult)
        } else {
            executeTool(inv.toolName, inv.arguments)
        }
        val state = if (result.first) ToolInvocationState.SUCCESS else ToolInvocationState.ERROR
        updateToolInvocationStateByKey(cid, waitingKeyOf(inv), state, result.second)

        // 用与登记时相同的键移除，避免 callId 为空时移不掉
        markToolResolved(cid, waitingKeyOf(inv))
    }
}

/**
 * 拒绝一次工具调用。
 *
 * 同样需要回填一条 tool 结果（说明「被拒绝」），
 * 否则 assistant.tool_calls 里这个 id 就永远没有配对项，后续每次请求都会 400。
 */
fun ChatViewModel.rejectToolCall(inv: ToolInvocation) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        updateToolInvocationStateByKey(
            cid,
            waitingKeyOf(inv),
            ToolInvocationState.REJECTED,
            PLACEHOLDER_REJECTED
        )
        markToolResolved(cid, waitingKeyOf(inv))
    }
}

/**
 * 标记某个工具调用已有结果，并在本轮全部处理完毕后开启下一轮生成。
 *
 * ## 参数说明
 *
 * [waitingKey] 必须是 [waitingKeyOf] 的产物，与登记时保持一致。
 *
 * 早期实现这里直接传 `inv.callId`，而登记时用的是 `callId.ifBlank { toolName }`。
 * 当 callId 为空串时，移除操作找不到任何匹配项，
 * 等待集合永远非空，表现为「工具执行完了但对话不继续」。
 *
 * ## 为什么移除与判定必须在同一个临界区
 *
 * 多个工具调用会被各自的协程并行处理。如果分成两步，会出现：
 *   - 都判定非空：没人触发续接，界面卡住
 *   - 都判定为空：重复触发，发出两次请求
 */
private suspend fun ChatViewModel.markToolResolved(cid: String, waitingKey: String) {
    val batch = activeBatch ?: return
    if (batch.conversationId != cid) return

    var shouldAdvance = false
    batch.lock.withLock {
        batch.waiting.remove(waitingKey)
        if (batch.waiting.isEmpty() && !batch.advanced) {
            batch.advanced = true
            shouldAdvance = true
        }
    }

    if (shouldAdvance) {
        DebugLog.i(TAG_TOOL, "本轮工具调用已全部处理完毕，开启下一轮生成")
        activeBatch = null
        triggerGeneration(cid)
    }
}

private suspend fun ChatViewModel.appendMessage(cid: String, msg: Message) {
    // 使用 ConversationStore 的原子追加，避免与流式 flush 抢同一把 key
    store.appendMessages(cid, listOf(msg))
}

private suspend fun ChatViewModel.updateToolInvocationState(
    cid: String,
    callId: String,
    state: String,
    resultOutput: String? = null
) {
    // 匹配口径必须与 waitingKeyOf / 流式归并保持一致：
    // 部分网关（如本地挂机AI）返回的 tool_call 不带 id（callId 为空串），
    // 此时各处统一退化为「工具名」作为键。
    //
    // 旧实现直接用 `inv.callId == callId` 裸比较，一旦某一侧是空串、
    // 另一侧是工具名（或相反），匹配就会静默失效：
    // 状态/结果写不进去，下一轮 buildApiMessages 读到的 inv 依旧 result 为空，
    // 最终报文里 tool 回复数为 0，上游返回 HTTP 400
    //   tool calls and tool results do not match
    // 这正是日志中「工具已执行完毕，却报 0 笔回复」的直接原因。
    val targetKey = callId.trim()

    var matched = 0
    store.updateMessages(cid) { msgs ->
        msgs.map { msg ->
            val invs = msg.toolInvocations.map { inv ->
                if (inv.callId.trim() == targetKey) {
                    matched++
                    inv.copy(state = state, result = resultOutput ?: inv.result)
                } else {
                    inv
                }
            }
            msg.copy(toolInvocations = invs)
        }
    }

    if (matched == 0) {
        // 不再静默失败：调用方本来就会在配对异常时报错，
        // 这里补一条明确日志，直接指向「回写没命中」这一环节。
        DebugLog.e(
            TAG_TOOL,
            "updateToolInvocationState 未命中任何工具调用：" +
                "callId='$callId' state=$state —— 状态未写入，" +
                "下一轮报文可能缺失对应的 tool 回复"
        )
    }
}

/**
 * 按 [waitingKeyOf] 计算出的键更新工具调用状态。
 *
 * 与 [updateToolInvocationState] 的区别：本函数用「callId 非空则用 callId，
 * 否则退化为工具名」的键匹配，与流式累积（第 772 行 `tc.id.ifBlank { tc.name }`）、
 * 进度登记/移除（[waitingKeyOf]）完全一致。
 *
 * 这样一来，「无 id 的工具调用」在整条链路上都用同一个键，
 * 不会再出现「进度集合按工具名命中、状态回写却按空 callId 落空」的错位，
 * 从根上避免下一轮报文出现 tool 回复为 0、上游返回 HTTP 400 的情况。
 */
private suspend fun ChatViewModel.updateToolInvocationStateByKey(
    cid: String,
    key: String,
    state: String,
    resultOutput: String? = null
) {
    var matched = 0
    store.updateMessages(cid) { msgs ->
        msgs.map { msg ->
            val invs = msg.toolInvocations.map { inv ->
                if (waitingKeyOf(inv) == key) {
                    matched++
                    inv.copy(state = state, result = resultOutput ?: inv.result)
                } else {
                    inv
                }
            }
            msg.copy(toolInvocations = invs)
        }
    }

    if (matched == 0) {
        DebugLog.e(
            TAG_TOOL,
            "updateToolInvocationStateByKey 未命中任何工具调用：" +
                "key='$key' state=$state —— 状态未写入，" +
                "下一轮报文可能缺失对应的 tool 回复"
        )
    }
}

/**
 * 为一次工具调用生成回填给模型的 tool 内容。
 *
 * OpenAI / Claude 的工具调用协议要求严格配对：
 * assistant 消息里出现的每一个 tool_calls[i].id，
 * 都必须在后续消息里找到一条 role=tool 且 tool_call_id 相同的回复。
 *
 * 旧实现只回填「result 非空」的那些：
 *
 *     msg.toolInvocations.forEach { inv ->
 *         if (inv.result.isNotBlank()) {
 *             apiMessages.add(ChatMessage(role = Role.TOOL, ...))
 *         }
 *     }
 *
 * 于是处于 PENDING_APPROVAL / REJECTED 状态（result 为空串）的工具调用
 * 就没有对应的 tool 消息，配对数量对不上。
 * 上游网关会直接返回 HTTP 400：
 *     tool calls and tool results do not match
 *
 * 这里保证每一笔 tool_call 都有且仅有一条 tool 回复，
 * 内容为空时用一个明确的占位说明代替，而不是留空或跳过。
 */
private fun buildToolResultText(inv: ToolInvocation): String {
    if (inv.result.isNotBlank()) return inv.result
    return when (inv.state) {
        ToolInvocationState.REJECTED -> PLACEHOLDER_REJECTED
        ToolInvocationState.PENDING_APPROVAL -> PLACEHOLDER_PENDING
        ToolInvocationState.EXECUTING, ToolInvocationState.RUNNING -> PLACEHOLDER_PENDING
        else -> inv.error?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_EMPTY
    }
}

/**
 * 一条 assistant 消息里已经确定好 id 的一笔工具调用。
 *
 * [invocation] 是它在本地会话里的原始记录，用于生成 tool 回复的内容；
 * [id] 是对外协议里使用的 call id，assistant 与 tool 两侧共用同一个值。
 *
 * 声明成 data class 是为了支持解构：
 * 普通 class 不生成 componentN()，用在解构位置会报
 * 「Destructuring ... requires operator function 'component1()'」。
 */
private data class PairedCall(val invocation: ToolInvocation, val id: String)

/**
 * 把本地会话历史转换成对外的 API 消息序列。
 *
 * ## 配对规则
 *
 * OpenAI / Claude 都要求：assistant.tool_calls 里每一个 id，
 * 都必须在紧随其后的消息里找到一一对应的 tool 回复。
 * 缺一个、多一个、顺序错乱，网关都会直接拒绝：
 *
 *     HTTP 400 tool calls and tool results do not match
 *
 * ## 本实现如何处理三个已知缺口
 *
 * ### 缺口一：tool_call_id 为空
 *
 * 部分网关返回的工具调用不带 id（或 id 为空串）。
 * 若 assistant 侧写 id=""，而 tool 侧写 tool_call_id=null，
 * 两侧就不是「同一个值」，配对失败。
 * 处理方式：为缺失 id 的调用生成 "call_local_N"，两侧共用。
 *
 * ### 缺口二：同一消息内 callId 重复
 *
 * 流式累积在极端分片下可能产出两笔 callId 相同的调用，
 * 部分网关判定重复 id 非法。
 * 处理方式：按 id 去重，只保留第一笔，其 tool 回复同步跳过。
 *
 * ### 缺口三：孤儿 tool 回复
 *
 * 早期版本可能写入 role=tool 的独立消息，找不到归属就是孤儿。
 * 处理方式：完全不读取 role=tool 的消息，
 * tool 回复一律由 assistant 的 toolInvocations 生成。
 *
 * ## 为什么用「一遍配对」而不是「两遍匹配」
 *
 * 曾经的做法是先建 tool_calls 列表，再遍历 toolInvocations，
 * 用「工具名 + 参数」反向查找对应的 id。这个做法在下面这种情况会出错：
 *
 *     同一个 assistant 里有两笔工具名和参数完全相同的调用
 *     （例如连读两次同一个文件）
 *
 * 反向查找会让两笔回复都命中同一个 id，产生重复 id 与数量错配。
 *
 * 现在改为一遍完成配对：先确定每笔调用各自的 id 与内容，
 * 再分别铺开成 tool_calls 与 tool 消息。因为是同一次遍历的产物，
 * 两侧的数量与 id 天然一一对应，不依赖任何反向查找。
 */
private suspend fun ChatViewModel.buildApiMessages(
    conv: Conversation,
    sysPrompt: String
): MutableList<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    if (sysPrompt.isNotBlank()) {
        out.add(ChatMessage(role = Role.SYSTEM, content = sysPrompt))
    }

    var callSeq = 0

    for (msg in conv.messages) {
        when (msg.role) {
            Role.USER -> {
                if (msg.content.isNotBlank()) {
                    out.add(ChatMessage(role = Role.USER, content = msg.content))
                }
            }

            Role.SYSTEM -> {
                // 会话内的系统消息（例如上下文压缩档案）按 system 角色原样带出，
                // 这些是用户上下文的一部分，不是发给模型之外的额外指令。
                if (msg.content.isNotBlank()) {
                    out.add(ChatMessage(role = Role.SYSTEM, content = msg.content))
                }
            }

            Role.TOOL -> {
                // 独立 role=tool 消息一律忽略：tool 回复只由 assistant 的
                // toolInvocations 生成，直接读取这类消息会产生无法追溯归属的孤儿。
                //
                // 这里必须记日志：如果会话里真的存在这类消息，
                // 说明历史数据有问题，而它可能正是「缺失的那一笔回复」。
                DebugLog.w(
                    TAG_TOOL,
                    "忽略会话内独立的 tool 角色消息：" +
                        "toolCallId='${msg.toolCallId}' 内容长度=${msg.content.length}"
                )
            }

            Role.ASSISTANT -> {
                val hasTools = msg.toolInvocations.isNotEmpty()
                val hasText = msg.content.isNotBlank()

                // 既没有正文也没有工具调用的 assistant 消息直接丢弃：
                // 例如上一轮流式异常中断留下的空壳消息。
                if (!hasText && !hasTools) continue

                if (!hasTools) {
                    out.add(ChatMessage(role = Role.ASSISTANT, content = msg.content))
                    continue
                }

                // ---------- 一遍完成配对 ----------
                val paired = mutableListOf<PairedCall>()
                val usedIds = HashSet<String>()

                for (inv in msg.toolInvocations) {
                    val rawId = inv.callId.trim()
                    val id = if (rawId.isNotEmpty()) rawId else "call_local_${callSeq++}"

                    if (!usedIds.add(id)) {
                        DebugLog.w(
                            TAG_TOOL,
                            "检测到重复的 callId='$id'（工具 ${inv.toolName}，" +
                                "状态=${inv.state}），已跳过重复项"
                        )
                        continue
                    }
                    paired.add(PairedCall(invocation = inv, id = id))
                }

                if (paired.isEmpty()) {
                    // 去重后一个都不剩（理论上不会发生），退化为纯文本 assistant
                    out.add(ChatMessage(role = Role.ASSISTANT, content = msg.content))
                    continue
                }

                // assistant 侧：完整的 tool_calls 数组
                out.add(
                    ChatMessage(
                        role = Role.ASSISTANT,
                        content = msg.content,
                        toolCalls = paired.map { p ->
                            ToolCall(
                                id = p.id,
                                name = p.invocation.toolName,
                                arguments = p.invocation.arguments.ifBlank { "{}" }
                            )
                        }
                    )
                )

                // tool 侧：逐条回复，数量与上面的 tool_calls 完全一致
                for (p in paired) {
                    out.add(
                        ChatMessage(
                            role = Role.TOOL,
                            content = buildToolResultText(p.invocation),
                            toolCallId = p.id
                        )
                    )
                }
            }
        }
    }

    // 末尾若是不带工具调用的 assistant（通常来自用户点停止留下的半截回复），
    // 上游会要求它必须带工具调用才能续写，直接丢弃这一条。
    while (out.isNotEmpty() &&
        out.last().role == Role.ASSISTANT &&
        out.last().toolCalls.isNullOrEmpty()
    ) {
        out.removeAt(out.lastIndex)
    }

    // ⚠️ 这里曾经有一段「无条件删除末尾的 tool 回复」的循环：
    //
    //     while (out.isNotEmpty() && out.last().role == Role.TOOL) {
    //         out.removeAt(out.lastIndex)
    //     }
    //
    // 它是本次「工具执行完、页面也显示了结果，但模型却收不到」的直接元凶。
    //
    // 根因：本函数的 tool 回复**不是**独立的消息，而是紧跟在 assistant
    // tool_calls 之后「成对合成」出来的（见上面 529–538 行的循环）。
    // 当会话的最后一条恰好是 assistant(tool_calls=[...]) 时，合成结果是
    //     [..., assistant(tool_calls), tool(reply)]
    // 末尾正好就是那条**合法且必须存在**的 tool 回复，却被这个循环当成
    // 「悬空回复」删掉了：
    //     [..., assistant(tool_calls=1)]   ← 声明 1 笔，回复 0 笔
    // 于是 validateToolPairing 报「缺失 tool 回复」，请求被本地拦截；
    // 页面上工具结果照常显示（那是本地 toolInvocations 的状态），
    // 但模型永远拿不到这笔回复 —— 表现就是「结果没反馈给 AI」。
    //
    // 正确做法：真正会悬空的 tool 回复，只可能出现在「上面 545 行循环把
    // 它的 assistant 丢掉」之后。那种情况由紧随其后的 validateToolPairing
    // 兜底判定并阻止发送即可，绝不能在这里按「角色是 tool」一刀切删除，
    // 因为无法区分「孤儿残留」与「刚刚成对合成的合法回复」。
    //
    // 因此：不再做无条件尾部删除，交给出参前的全量配对校验决定去留。

    // 只有当末尾是「不带工具调用的 assistant」（通常来自用户点停止留下的半截回复）
    // 时，上游才要求把它丢弃；一旦丢弃，紧跟在它前面的 tool 回复确实会变孤儿。
    //
    // 关键区别：这里绝不能按「角色是 tool」一刀切删除 —— 那样会连
    // 刚刚成对合成的合法回复一起删掉（本次 bug）。只有当一条末尾 tool 回复
    // 的 toolCallId **找不到**对应的 assistant.tool_calls 声明时，它才是真孤儿。
    val declaredIdsInOut = buildSet {
        for (m in out) {
            if (m.role == Role.ASSISTANT && !m.toolCalls.isNullOrEmpty()) {
                for (tc in m.toolCalls) if (tc.id.isNotBlank()) add(tc.id)
            }
        }
    }
    while (out.isNotEmpty() &&
        out.last().role == Role.TOOL &&
        (out.last().toolCallId.isNullOrBlank() || out.last().toolCallId !in declaredIdsInOut)
    ) {
        out.removeAt(out.lastIndex)
    }

    // 构建完成后做一次全量配对校验。
    // 校验不通过时不允许继续发送：把带病报文发给上游必然被拒（HTTP 400
    // tool calls and tool results do not match），不如在本地就拦下并给出可读提示。
    val issues = validateToolPairing(out)
    if (issues.isNotEmpty()) {
        throw IllegalStateException(
            "工具调用配对异常，已阻止发送以免上游 400：" +
                issues.joinToString("；") +
                "。可尝试新建对话后重试。"
        )
    }

    return out
}

/**
 * 校验 tool_calls 与 tool 回复的配对完整性，并把结果写入日志。
 *
 * 校验规则：
 *   1. 每个 assistant.tool_calls[i].id 都必须有对应的 tool 回复
 *   2. 每条 tool 回复都必须能追溯到某个 assistant.tool_calls
 *   3. 同一个 id 不应在同一组 tool_calls 里重复出现
 *
 * ## 发现异常时会额外 dump 参与校验的消息序列
 *
 * 只报「缺失 call_xxx」是不够的 —— 真正要回答的是
 * 「这笔调用是在哪条 assistant 上声明的、它后面本该跟几条回复、
 *   实际只跟了几条」，才能定位到是会话历史坏了还是构建逻辑漏了。
 *
 * dump 只取末尾 [PAIRING_DUMP_LIMIT] 条：
 * 长会话逐条打印会淹没日志，而问题基本都出在最近几轮。
 *
 * ## 与 LlmClient.debugToolPairingInBody 的分工
 *
 * 这一处校验「本地会话 → ChatMessage」的转换结果；
 * 那一处校验「ChatMessage → 真正发出的报文」。
 * 两边都异常说明问题在会话历史，只有一边异常说明问题在对应环节。
 */
private fun validateToolPairing(messages: List<ChatMessage>): List<String> {
    val declared = mutableSetOf<String>()
    val answered = mutableSetOf<String>()
    val duplicates = mutableListOf<String>()
    val issues = mutableListOf<String>()

    for (msg in messages) {
        if (msg.role == Role.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
            for (tc in msg.toolCalls) {
                if (!declared.add(tc.id)) {
                    duplicates.add(tc.id)
                }
            }
        } else if (msg.role == Role.TOOL) {
            val id = msg.toolCallId
            if (id.isNullOrBlank()) {
                issues.add("存在 tool_call_id 为空的 tool 回复")
            } else {
                if (id !in declared) {
                    issues.add("孤儿 tool 回复：id='$id' 找不到对应的 tool_calls")
                }
                answered.add(id)
            }
        }
    }

    val missing = declared - answered
    if (missing.isNotEmpty()) {
        issues.add("缺失 tool 回复：${missing.joinToString("、")}")
    }
    if (duplicates.isNotEmpty()) {
        issues.add("重复的 tool_call id：${duplicates.distinct().joinToString("、")}")
    }

    if (issues.isEmpty()) {
        DebugLog.d(
            TAG_TOOL,
            "工具调用配对正常：声明 ${declared.size} 笔，回复 ${answered.size} 笔"
        )
        return emptyList()
    }

    DebugLog.e(
        TAG_TOOL,
        "工具调用配对异常（共 ${messages.size} 条消息，" +
            "声明 ${declared.size} 笔，回复 ${answered.size} 笔）：\n" +
            issues.joinToString("\n") { "  · $it" } +
            "\n" + dumpMessageSequence(messages)
    )

    return issues
}

/**
 * 生成消息序列的可读 dump，用于定位配对缺口的具体位置。
 *
 * 每条消息输出一行，格式：
 *
 * ```
 * [14] assistant tool_calls=2 [call_a、call_b] text=12字
 * [15] tool tool_call_id=call_a len=340
 * [16] tool tool_call_id=call_b len=28
 * ```
 *
 * 从这样的序列里可以直接看出「哪条 assistant 声明了 N 笔、
 * 后面实际跟了几条 tool 回复」，缺口一目了然。
 */
private fun dumpMessageSequence(messages: List<ChatMessage>): String {
    val start = (messages.size - PAIRING_DUMP_LIMIT).coerceAtLeast(0)
    val sb = StringBuilder()
    sb.append("──────── 消息序列（末 ${messages.size - start} 条）────────")
    for (i in start until messages.size) {
        val m = messages[i]
        sb.append('\n').append('[').append(i).append("] ").append(m.role)
        when {
            m.role == Role.ASSISTANT && !m.toolCalls.isNullOrEmpty() -> {
                sb.append(" tool_calls=").append(m.toolCalls.size).append(" [")
                m.toolCalls.forEachIndexed { idx, tc ->
                    if (idx > 0) sb.append('、')
                    sb.append(tc.id.ifBlank { "(空id)" })
                }
                sb.append("] text=").append(m.content.length).append("字")
            }

            m.role == Role.TOOL -> {
                sb.append(" tool_call_id=").append(m.toolCallId?.ifBlank { "(空)" } ?: "(null)")
                sb.append(" len=").append(m.content.length)
            }

            else -> {
                sb.append(" text=").append(m.content.length).append("字")
            }
        }
    }
    return sb.toString()
}

private fun ChatViewModel.triggerGeneration(cid: String) {
    streamJob?.cancel()
    streamJob = viewModelScope.launch {
        _isStreaming.value = true
        _streamingOverlay.value = null

        val conv = store.snapshot().find { it.id == cid } ?: return@launch
        val prov = activeProvider() ?: run {
            _error.value = "未配置有效的模型提供商"
            _isStreaming.value = false
            return@launch
        }
        val model = settings.value.activeModel.ifBlank { prov.models.firstOrNull()?.modelId } ?: run {
            _error.value = "未选择模型"
            _isStreaming.value = false
            return@launch
        }

        val assistant = activeAssistant()
        val sysPrompt = assistant?.systemPrompt?.ifBlank { settings.value.systemPrompt }
            ?: settings.value.systemPrompt

        val apiMessages = try {
            buildApiMessages(conv, sysPrompt)
        } catch (e: IllegalStateException) {
            // 配对校验未通过：本地直接拦下，不发送给上游。
            // 否则上游必回 HTTP 400（tool calls and tool results do not match），
            // 用户只看到一句没有上下文的报错，反而更难定位。
            DebugLog.e(TAG_TOOL, "已阻止发送带病报文：${e.message}")
            _error.value = e.message
            _isStreaming.value = false
            return@launch
        }

        if (apiMessages.none { it.role == Role.USER || it.role == Role.TOOL }) {
            DebugLog.w(TAG_TOOL, "会话 $cid 缺少可发送的用户消息，跳过本轮生成")
            _isStreaming.value = false
            return@launch
        }

        val cacheMode = settings.value.aiCacheMode
        val compressionResult: CompressionResult = cacheManager.compressContext(cacheMode, apiMessages)
        val finalApiMessages = compressionResult.processedMessages
        val aliasMap = compressionResult.aliasMap

        val assistantMsg = Message(
            role = Role.ASSISTANT,
            content = "",
            providerId = prov.id,
            providerName = prov.name.ifBlank { prov.id },
            model = model
        )
        appendMessage(cid, assistantMsg)

        var accumulatedText = ""
        var accumulatedReasoning = ""
        val toolInvocations = mutableListOf<ToolInvocation>()
        val startTime = SystemClock.elapsedRealtime()

        // 注意：这里刻意不做任何历史数据自愈。
        // 早期版本在发请求前调用 repairOrphanToolOwnership()，会在流式进行中
        // 重写 mcp_tools_json，与 RevMcpHost 的后台拉取产生读—改—写竞争，
        // 反而把刚拉到的工具记录覆盖掉。现在工具归属只由用户显式操作维护。
        val tools = collectAvailableTools()
        DebugLog.i(
            TAG_TOOL,
            "本轮注入工具 ${tools.size} 个：${tools.joinToString("、") { it.name }}"
        )

        // 每轮生成开始时建立新的批次，替换掉上一轮可能遗留的进度
        val batch = ToolBatch(cid)
        activeBatch = batch

        try {
            var promptTokensReported = 0
            var completionTokensReported = 0

            var lastFlushAt = 0L
            var lastFlushedText = ""

            suspend fun flushToStore(force: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastFlushAt < FLUSH_INTERVAL_MS) return
                if (!force && accumulatedText == lastFlushedText) return
                lastFlushAt = now
                lastFlushedText = accumulatedText

                val nowDuration = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)
                val estimatedOutTokens = if (completionTokensReported > 0) {
                    completionTokensReported
                } else {
                    (accumulatedText.length / 2).coerceAtLeast(1)
                }
                val promptEst = if (promptTokensReported > 0) {
                    promptTokensReported
                } else {
                    finalApiMessages.sumOf { it.content.length } / 2
                }
                val currentUsage = TokenUsage(
                    promptTokens = promptEst,
                    completionTokens = estimatedOutTokens,
                    totalTokens = promptEst + estimatedOutTokens,
                    durationMs = nowDuration,
                    tokensPerSecond = (estimatedOutTokens * 1000f) / nowDuration
                )

                val textSnapshot = accumulatedText
                val reasoningSnapshot = accumulatedReasoning.ifBlank { null }
                val invocationsSnapshot = toolInvocations.toList()

                // 增量更新：只改写目标会话的这一条消息，不重建整个列表
                store.updateMessages(cid) { msgs ->
                    val targetIdx = msgs.indexOfLast { it.id == assistantMsg.id }
                    if (targetIdx < 0) {
                        msgs
                    } else {
                        msgs.toMutableList().also { list ->
                            list[targetIdx] = list[targetIdx].copy(
                                content = textSnapshot,
                                reasoningContent = reasoningSnapshot,
                                toolInvocations = invocationsSnapshot,
                                usage = currentUsage
                            )
                        }
                    }
                }
            }

            DebugLog.i(TAG_TOOL, "开始接收流式响应 conv=$cid")
            var deltaCount = 0

            client.chatStream(
                provider = prov,
                settings = settings.value,
                modelId = model,
                messages = finalApiMessages,
                tools = tools
            ).collect { delta ->
                deltaCount++

                // 只处理真正有内容的文本帧。
                //
                // StreamDelta.text 是「非空 String」类型，默认值为空串，
                // 因此 `delta.text?.let { }` 对空串同样会执行。
                // usage / reasoning 帧的 text 都是空串，若不加判断，
                // 每收到这类帧都会重复执行一次 overlay 赋值与解压调用，
                // 平白增加无意义的开销。
                val deltaText = delta.text
                if (deltaText.isNotEmpty()) {
                    accumulatedText += cacheManager.decompressText(deltaText, aliasMap)
                    // overlay 是轻量 StateFlow，可以每 delta 更新，不涉及磁盘
                    _streamingOverlay.value = accumulatedText
                }
                delta.reasoning?.let {
                    if (it.isNotEmpty()) accumulatedReasoning += it
                }
                delta.usage?.let { u ->
                    promptTokensReported = u.promptTokens
                    completionTokensReported = u.completionTokens
                }
                delta.toolCalls?.forEach { tc ->
                    DebugLog.i(
                        TAG_TOOL,
                        "模型请求调用工具 name='${tc.name}' id='${tc.id}' " +
                            "args=${tc.arguments.take(200)}"
                    )
                    // 按 callId 归并增量分片；id 为空时退化为按工具名归并
                    val key = tc.id.ifBlank { tc.name }
                    val existing = toolInvocations.indexOfFirst {
                        (it.callId.ifBlank { it.toolName }) == key
                    }
                    if (existing >= 0) {
                        val cur = toolInvocations[existing]
                        toolInvocations[existing] = cur.copy(
                            arguments = tc.arguments.ifBlank { cur.arguments }
                        )
                    } else {
                        toolInvocations.add(
                            ToolInvocation(
                                callId = tc.id,
                                toolName = tc.name,
                                arguments = tc.arguments,
                                state = ToolInvocationState.PENDING_APPROVAL
                            )
                        )
                    }
                    // 工具调用出现或参数变化时立刻落盘，保证审批卡片即时可交互
                    flushToStore(force = true)
                }

                flushToStore(force = false)
            }

            DebugLog.i(
                TAG_TOOL,
                "流式响应接收完毕 conv=$cid 帧数=$deltaCount " +
                    "文本长度=${accumulatedText.length} 工具调用数=${toolInvocations.size}"
            )

            // 流结束，强制落盘一次，确保最终内容不丢
            flushToStore(force = true)

            if (accumulatedText.isBlank() && toolInvocations.isEmpty()) {
                val fallbackText = if (accumulatedReasoning.isNotBlank()) {
                    "（已完成思考，未返回文本）"
                } else {
                    "（无响应内容）"
                }
                store.updateMessages(cid) { msgs ->
                    val targetIdx = msgs.indexOfLast { it.id == assistantMsg.id }
                    if (targetIdx < 0) {
                        msgs
                    } else {
                        msgs.toMutableList().also { list ->
                            list[targetIdx] = list[targetIdx].copy(content = fallbackText)
                        }
                    }
                }
            }

            // 关键逻辑：在首轮对话完成后，自动调用标题生成
            generateTitleIfNeeded(cid)

            // 登记本轮需要处理的工具调用，再由 checkAutoApproveTools 逐个分流。
            // 键的计算统一走 waitingKeyOf，保证与 markToolResolved 两侧一致。
            batch.lock.withLock {
                batch.waiting.clear()
                toolInvocations.forEach { batch.waiting.add(waitingKeyOf(it)) }
            }

            if (batch.waiting.isEmpty()) {
                // 本轮没有工具调用，无需等待，批次直接作废
                if (activeBatch === batch) activeBatch = null
            } else {
                DebugLog.i(
                    TAG_TOOL,
                    "本轮共 ${batch.waiting.size} 个工具调用待处理：" +
                        batch.waiting.joinToString("、")
                )
                checkAutoApproveTools(cid, batch, toolInvocations)
            }

        } catch (e: Exception) {
            _error.value = "生成失败: ${e.message}"
            // 异常中断时作废本轮批次，避免残留等待项永久阻塞后续续接
            if (activeBatch === batch) activeBatch = null
            if (accumulatedText.isBlank() && toolInvocations.isEmpty()) {
                val errText = "请求失败: ${e.message}"
                store.updateMessages(cid) { msgs ->
                    val targetIdx = msgs.indexOfLast { it.id == assistantMsg.id }
                    if (targetIdx < 0) {
                        msgs
                    } else {
                        msgs.toMutableList().also { list ->
                            list[targetIdx] = list[targetIdx].copy(content = errText)
                        }
                    }
                }
            }
        } finally {
            DebugLog.i(TAG_TOOL, "本轮生成结束 conv=$cid")
            _isStreaming.value = false
            _streamingOverlay.value = null
            streamJob = null
        }
    }
}

/**
 * 收集本轮可注入给模型的工具清单。
 *
 * ## 授权模型
 *
 * 每个工具都有一条独立的权限记录 [com.miniichatNext.carter.data.mcp.McpToolPermission]：
 *
 *   enabled          该工具是否允许 AI 使用（主开关）
 *   requireApproval  调用前是否需要用户点【同意】（审批开关）
 *
 * 两个开关职责完全分开：
 *
 *   默认              没有权限记录            -> 不注入，模型根本看不到这个工具
 *   用户打开主开关     enabled=true            -> 注入，调用时要审批（除非同时关了审批）
 *   用户关掉审批开关   enabled=true, 免审批    -> 注入，调用时自动执行
 *
 * ## 唯一例外
 *
 * 内置的 interactive_clarification 始终注入。
 * 它是模型向用户提问的通道，不属于「操作本机资源」的范畴，
 * 关掉它只会让模型在需要确认需求时无从下手。
 *
 * ## 关于筛选与循环写法
 *
 * 权限判定抽成局部同步函数，循环体本身不调用任何 suspend 函数、
 * 也不做跨 lambda 的 continue。这样做有两个好处：
 *
 *   1. 每个工具的权限查询都读一次 DataStore，逐个 await 会放大到 O(n) 次读。
 *      先取一份权限快照再纯内存匹配，只读一次。
 *   2. 循环体保持纯同步之后，不需要任何提前跳出语句，
 *      自然也就不会碰到 break/continue 跨 inline lambda 边界的语言版本限制。
 */
private suspend fun ChatViewModel.collectAvailableTools(): List<ToolSpec> {
    // 取一份权限快照，后续全部在内存里判断，不再逐个查询
    val perms = mcpStore.permsSnapshot()

    fun isEnabled(toolName: String, scopeId: String): Boolean {
        if (toolName == BuiltInFileTools.CLARIFICATION_TOOL) return true
        val perm = perms.firstOrNull { it.toolName == toolName && it.serverId == scopeId }
        // 无权限记录时按默认值处理：默认全开，工具可用。
        // 只有用户显式关闭（enabled=false）才不注入。
        return perm?.enabled ?: true
    }

    val out = mutableListOf<ToolSpec>()

    // ---------- 1. 内置工具：仅注入用户明确开启的 ----------
    val builtInEnabled = BuiltInFileTools.schema.filter { t ->
        isEnabled(t.name, BuiltInFileTools.BUILTIN_PERMISSION_ID)
    }
    for (t in builtInEnabled) {
        out.add(
            ToolSpec(
                name = t.name,
                description = t.description,
                inputSchema = t.inputSchema
            )
        )
    }

    // ---------- 2. 外部 MCP 工具：服务器启用 + 工具本身启用 ----------
    val servers = mcpStore.snapshot()
    val enabledServerIds = servers.filter { it.enabled }.map { it.id }.toSet()
    if (enabledServerIds.isEmpty()) return out

    val alreadyIncluded = out.map { it.name }.toSet()
    val externalEnabled = mcpStore.toolsSnapshot().filter { t ->
        t.serverId.isNotBlank() &&
            t.serverId in enabledServerIds &&
            isEnabled(t.name, t.serverId) &&
            // 与内置工具重名时以内置实现优先，避免执行期走错分支
            t.name !in alreadyIncluded
    }
    for (t in externalEnabled) {
        out.add(
            ToolSpec(
                name = t.name,
                description = t.description,
                inputSchema = t.inputSchema
            )
        )
    }

    return out
}

/**
 * 分流本轮的工具调用：需要审批的保持 PENDING 等用户操作，免审批的直接执行。
 *
 * 本轮所有等待项的键已由调用方登记进 [batch].waiting。
 * 这里只负责「决定每个工具怎么处理」，不负责判断何时续接 ——
 * 续接统一由 [markToolResolved] 在最后一个工具出结果时触发。
 *
 * ## 关于循环写法
 *
 * 全部用普通 for + continue，没有把 continue 写进任何 lambda。
 *
 * 特别注意不能写成 `x ?: run { ...; continue }`：
 * run 是 inline 函数，在它的 lambda 里执行 continue，
 * 属于「跨越 inline lambda 边界向外层循环跳转」，
 * 需要 Kotlin 2.2 才稳定的 break continue in inline lambdas 语言特性，
 * 在当前语言版本下会直接编译失败。
 */
private suspend fun ChatViewModel.checkAutoApproveTools(
    cid: String,
    batch: ToolBatch,
    toolInvocations: List<ToolInvocation>
) {
    for (inv in toolInvocations) {
        if (inv.state != ToolInvocationState.PENDING_APPROVAL) continue

        // 需求澄清必须由用户本人作答，任何自动审批策略都不能越过它。
        // 它会一直留在 waiting 集合里，直到用户在卡片上提交选择，
        // 那时 approveToolCall 会走 markToolResolved 汇报进度。
        if (inv.toolName == BuiltInFileTools.CLARIFICATION_TOOL) continue

        // 两段式：先取值，再判空跳过。
        // 不写成 `?: run { ...; continue }`，原因见函数头注释。
        val scope = resolveToolScope(inv.toolName)
        if (scope == null) {
            DebugLog.w(TAG_TOOL, "无法解析工具 '${inv.toolName}' 的权限归属，保持等待审批")
            continue
        }

        val perm = mcpStore.findPermission(scope.toolName, scope.permissionScopeId)

        // 无权限记录：按默认值处理（默认免审批），直接自动执行。
        // 只有用户显式保留了审批开关（requireApproval=true）才会走下面的等待分支。
        if (perm == null) {
            DebugLog.i(TAG_TOOL, "工具 ${scope.toolName} 无权限记录，按默认免审批自动执行")
            approveToolCall(inv)
            continue
        }

        // 工具在使用过程中被用户关掉了，同样保持等待审批由用户处理
        if (!perm.enabled) {
            DebugLog.w(TAG_TOOL, "工具 ${scope.toolName} 已被关闭，等待用户处理")
            continue
        }

        if (!perm.requireApproval) {
            DebugLog.i(TAG_TOOL, "工具 ${scope.toolName} 免审批，自动执行")
            approveToolCall(inv)
        }
    }
}

/**
 * 一个工具在本地系统中的完整定位信息。
 *
 * [toolName]           归一化之后的真实工具名
 * [permissionScopeId]  权限记录里 serverId 字段应取的値
 *                      （内置工具为 "builtin"，外部工具为其所属服务器 id）
 * [builtIn]            是否为内置工具
 */
private data class ToolScope(
    val toolName: String,
    val permissionScopeId: String,
    val builtIn: Boolean
)

/**
 * 把模型给出的工具名解析成完整定位信息。
 *
 * 解析顺序：
 *   1. 内置工具表精确匹配
 *   2. 外部工具表精确匹配（同时取出它所属的服务器 id 作为权限归属）
 *   3. 按分隔符剥离前缀后重新匹配
 *
 * 解析不出归属时返回 null，由调用方按保守策略处理。
 */
private suspend fun ChatViewModel.resolveToolScope(rawName: String): ToolScope? {
    // 1. 内置工具
    if (BuiltInFileTools.isBuiltIn(rawName)) {
        return ToolScope(
            toolName = rawName,
            permissionScopeId = BuiltInFileTools.BUILTIN_PERMISSION_ID,
            builtIn = true
        )
    }

    // 2. 外部工具：拿 name -> serverId 的映射，权限按服务器隔离
    val servers = mcpStore.snapshot()
    val enabledServerIds = servers.filter { it.enabled }.map { it.id }.toSet()
    val externalTools = mcpStore.toolsSnapshot()
        .filter { it.serverId.isNotBlank() && it.serverId in enabledServerIds }

    val exact = externalTools.firstOrNull { it.name == rawName }
    if (exact != null) {
        return ToolScope(
            toolName = exact.name,
            permissionScopeId = exact.serverId,
            builtIn = false
        )
    }

    // 3. 归一化后重新匹配
    val allBuiltInNames = BuiltInFileTools.schema.map { it.name }
    val allExternalNames = externalTools.map { it.name }
    val resolvedName = ToolNameResolver.resolve(rawName, allBuiltInNames + allExternalNames)
        ?: return null

    if (resolvedName != rawName) {
        DebugLog.i(TAG_TOOL, "工具名归一化：'$rawName' -> '$resolvedName'")
    }

    if (BuiltInFileTools.isBuiltIn(resolvedName)) {
        return ToolScope(
            toolName = resolvedName,
            permissionScopeId = BuiltInFileTools.BUILTIN_PERMISSION_ID,
            builtIn = true
        )
    }

    val matched = externalTools.firstOrNull { it.name == resolvedName } ?: return null
    return ToolScope(
        toolName = matched.name,
        permissionScopeId = matched.serverId,
        builtIn = false
    )
}

/**
 * 把模型给出的工具名解析成本地真实存在的名字。
 *
 * 用于执行阶段的路由，只关心名字本身，不关心权限归属。
 */
private suspend fun ChatViewModel.resolveToolNameOrSelf(rawName: String): String {
    if (BuiltInFileTools.isBuiltIn(rawName)) return rawName

    val externalNames = mcpStore.toolsSnapshot()
        .filter { it.serverId.isNotBlank() }
        .map { it.name }
    if (rawName in externalNames) return rawName

    val allNames = BuiltInFileTools.schema.map { it.name } + externalNames
    val resolved = ToolNameResolver.resolve(rawName, allNames)
    if (resolved != null && resolved != rawName) {
        DebugLog.i(TAG_TOOL, "执行阶段归一化：'$rawName' -> '$resolved'")
    }
    return resolved ?: rawName
}

/**
 * 工具执行路由。
 *
 * 路由优先级：
 *   1. 工具名归一化（处理带前缀的旧名字）
 *   2. 内置工具 -> 直接走内存实现，绝不依赖任何持久化状态
 *   3. 外部 MCP 工具 -> 按 toolName + serverId 定位下游服务器
 *
 * 所有失败路径都会写入 DebugLog，并把「实际收到的名字」与「候选归一化结果」
 * 一并回给模型，便于用户在对话里直接看到问题所在。
 */
private suspend fun ChatViewModel.executeTool(
    toolName: String,
    argsJson: String
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    // ---------- 0. 名字归一化 ----------
    val resolvedName = resolveToolNameOrSelf(toolName)
    if (resolvedName != toolName) {
        DebugLog.i(TAG_TOOL, "执行阶段归一化：'$toolName' -> '$resolvedName'")
    }

    // ---------- 1. 内置工具 ----------
    if (BuiltInFileTools.isBuiltIn(resolvedName)) {
        val res = BuiltInFileTools.execute(resolvedName, argsJson)
        val content = if (res.ok) res.content else (res.error ?: "内置工具执行失败")
        return@withContext Pair(res.ok, content)
    }

    // ---------- 2. 外部 MCP 工具 ----------
    val servers = mcpStore.snapshot()
    val candidates = mcpStore.toolsSnapshot().filter { it.name == resolvedName }

    if (candidates.isEmpty()) {
        val builtInNames = BuiltInFileTools.schema.joinToString("、") { it.name }
        val allExternalTools = mcpStore.toolsSnapshot().filter { it.serverId.isNotBlank() }
        val externalNames = allExternalTools.joinToString("、") { it.name }.ifBlank { "（无）" }

        DebugLog.e(
            TAG_TOOL,
            "未知工具：收到='$toolName' 归一化='$resolvedName'。\n" +
                "内置工具数=${BuiltInFileTools.schema.size}，" +
                "外部工具数=${allExternalTools.size}，" +
                "外部工具名=$externalNames"
        )

        return@withContext Pair(
            false,
            buildString {
                append("未知工具：")
                append(if (toolName.isBlank()) "（工具名为空）" else toolName)
                if (resolvedName != toolName && resolvedName.isNotBlank()) {
                    append("（已尝试归一化为 ").append(resolvedName).append("）")
                }
                append('\n')
                if (toolName.isBlank()) {
                    append("模型返回的工具调用缺少名称。这通常是上游接口的兼容问题，")
                    append("请尝试在提供商设置中切换流式 / 非流式模式后重试。\n")
                }
                append("· 内置工具：").append(builtInNames).append('\n')
                append("· 外部 MCP 工具：").append(externalNames).append('\n')
                append("请只使用上面列出的工具名之一。若列表为空，")
                append("请先在 MCP 管理页确认对应服务器已启用并重新拉取工具列表。")
            }
        )
    }

    val resolved: Pair<McpTool, McpServerConfig>? = candidates.firstNotNullOfOrNull { t ->
        val s = servers.find { it.id == t.serverId && it.enabled }
        if (s != null) t to s else null
    }

    if (resolved == null) {
        val detail = candidates
            .map { it.serverId.ifBlank { "(未标注归属)" } }
            .distinct()
            .joinToString("、") { sid ->
                val s = servers.find { it.id == sid }
                when {
                    sid == "(未标注归属)" -> "归属缺失（历史数据，请在 MCP 管理页重新拉取该服务器）"
                    s == null -> "$sid（服务器已删除）"
                    !s.enabled -> "$sid（服务器当前已禁用）"
                    else -> sid
                }
            }
        DebugLog.w(TAG_TOOL, "工具 $resolvedName 归属服务器不可用：$detail")
        return@withContext Pair(
            false,
            buildString {
                append("工具 ").append(resolvedName).append(" 当前不可执行。\n")
                append("归属服务器状态：").append(detail).append('\n')
                append("请到 MCP 管理页启用对应服务器，或重新拉取该服务器的工具列表。")
            }
        )
    }

    val (_, server) = resolved
    val res = McpClient().callTool(server, resolvedName, argsJson)
    if (res.ok) {
        Pair(true, res.content)
    } else {
        Pair(false, res.error ?: "MCP 工具执行失败，下游未返回错误详情")
    }
}
