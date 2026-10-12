package com.miniichatNext.carter.data.compress

import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.Role

/**
 * 会话上下文压缩引擎。
 *
 * 设计目标：让压缩后的摘要成为「可继续干活的施工图纸」，而不是「这段对话说了什么」的读后感。
 *
 * 与旧实现的核心差异：
 *   旧实现把分块结果再收敛成「一句话」，导致文件路径、函数签名、变量名、
 *   已读取的代码结论、待办事项全部丢失，AI 后续无法基于历史继续工作。
 *   本实现改为输出结构化清单，强制保留可执行信息。
 *
 * 实现要点：
 *   1. 按「消息边界」分块，绝不从一条消息中间切开 —— 半截 JSON / 半截代码
 *      会让模型看到残缺片段，提炼出的摘要必然是错的。
 *   2. 每条消息带显式编号与角色标签，便于模型引用与去重。
 *   3. 工具调用与其结果成对呈现，让摘要能保留「读过哪个文件、调过什么工具」。
 *   4. 分块摘要与最终摘要都使用同一个结构化模板，避免二次收敛时信息丢失。
 *   5. 最终摘要不设字数上限，只设「必须包含的字段」清单。
 */

/** 单块的最大字符数，留足安全缓冲以规避渠道侧的 10000 字符硬限 */
private const val MAX_CHUNK_CHARS = 6000

/** 单条消息在进入分块前的最大保留字符数，防止一条超长代码把整块撑爆 */
private const val MAX_MESSAGE_CHARS = 12000

/** 结构化摘要必须覆盖的字段清单，分块与最终收敛共用 */
private val REQUIRED_SECTIONS = listOf(
    "【任务目标】用户最终想要达成的结果",
    "【已完成事项】已经做过什么、产出了什么",
    "【关键文件与路径】涉及的文件完整路径、类名、方法名、变量名",
    "【代码与技术要点】关键代码片段、函数签名、配置项、依赖库与版本",
    "【已确认的结论与决策】验证过的结论、选定的方案、排除掉的做法及原因",
    "【待办与未解决问题】还没做完的事、报错信息、下一步计划",
    "【约束与偏好】用户明确提出的格式要求、命名规范、技术选型偏好"
)

/**
 * 把会话消息列表切成若干块。
 *
 * 分块在两个层级上进行：
 *   - 消息级：正常情况下一次放多条消息，直到累计长度接近 [MAX_CHUNK_CHARS]。
 *   - 消息内：单条消息自身就超限时，按行在消息内部切片（见 [splitOversizedBlock]）。
 *
 * @return 分块结果，每块是一段可直接喂给模型的文本
 */
internal fun buildChunks(messages: List<Message>): List<String> {
    if (messages.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    val buffer = StringBuilder()
    var index = 1

    fun flush() {
        if (buffer.isNotEmpty()) {
            chunks.add(buffer.toString())
            buffer.clear()
        }
    }

    messages.forEach { msg ->
        val block = renderMessage(msg, index)
        val currentIndex = index
        index++

        // 单条消息自身超过块上限：它独占一块或多块
        if (block.length > MAX_CHUNK_CHARS) {
            flush()
            chunks.addAll(splitOversizedBlock(msg, currentIndex))
            return@forEach
        }

        if (buffer.length + block.length > MAX_CHUNK_CHARS) {
            flush()
        }
        buffer.append(block)
    }
    flush()
    return chunks
}

/** 把一条消息渲染成带编号与角色的文本块 */
private fun renderMessage(msg: Message, index: Int): String {
    val roleLabel = roleLabelOf(msg.role)

    val sb = StringBuilder()
    sb.append("### #").append(index).append(" [").append(roleLabel).append("]\n")

    val content = msg.content
    if (content.isNotBlank()) {
        if (content.length > MAX_MESSAGE_CHARS) {
            sb.append(content, 0, MAX_MESSAGE_CHARS)
            sb.append("\n...(本条消息过长，已截断 ")
            sb.append(content.length - MAX_MESSAGE_CHARS)
            sb.append(" 字符)\n")
        } else {
            sb.append(content).append('\n')
        }
    }

    // 工具调用与其结果成对呈现，这是保留「读过什么文件、调过什么工具」的关键
    if (msg.toolInvocations.isNotEmpty()) {
        sb.append("[本轮工具调用]\n")
        msg.toolInvocations.forEach { inv ->
            sb.append("  - 工具: ").append(inv.toolName)
            sb.append(" | 状态: ").append(inv.state).append('\n')
            if (inv.arguments.isNotBlank()) {
                sb.append("    参数: ").append(inv.arguments.take(1200)).append('\n')
            }
            if (inv.result.isNotBlank()) {
                sb.append("    结果: ").append(inv.result.take(3000)).append('\n')
            }
            inv.error?.let { sb.append("    错误: ").append(it.take(600)).append('\n') }
        }
    }

    sb.append('\n')
    return sb.toString()
}

/** 角色标签映射，分块与超长切片共用 */
private fun roleLabelOf(role: String): String = when (role) {
    Role.USER -> "用户"
    Role.ASSISTANT -> "助手"
    Role.SYSTEM -> "系统"
    Role.TOOL -> "工具"
    else -> role
}

/**
 * 处理超长单条消息。
 *
 * 按行累积切片：只有在「加入下一行会超出块上限」时才断开，
 * 因此断开点一定落在行边界上，不会把一行代码或一个 JSON 字段从中间劈开。
 * 每一片都带上续接标记，保证模型知道这是同一条消息的后续部分。
 */
private fun splitOversizedBlock(msg: Message, msgIndex: Int): List<String> {
    val roleLabel = roleLabelOf(msg.role)
    val header = "### #$msgIndex [$roleLabel]（消息过长，分片呈现）\n"
    val body = msg.content
    val parts = mutableListOf<String>()
    val buffer = StringBuilder(header)
    var part = 1

    body.lines().forEach { line ->
        if (buffer.length + line.length + 1 > MAX_CHUNK_CHARS) {
            parts.add(buffer.toString())
            buffer.clear()
            buffer.append("### #").append(msgIndex)
                .append("（续 ").append(part).append("）\n")
            part++
        }
        buffer.append(line).append('\n')
    }
    if (buffer.length > header.length) parts.add(buffer.toString())
    if (parts.isEmpty()) parts.add(header)
    return parts
}

/** 构造单个分块的提炼请求消息 */
internal fun buildChunkRequest(
    chunkText: String,
    chunkIndex: Int,
    chunkTotal: Int
): List<ChatMessage> {
    val system = buildString {
        appendLine("你是一名严谨的技术档案整理员，负责把一段对话历史压缩成可继续施工的技术摘要。")
        appendLine()
        appendLine("你的输出会作为后续对话唯一的上下文依据，因此：")
        appendLine("- 必须保留所有可执行信息：文件完整路径、类名、方法名、变量名、函数签名、")
        appendLine("  命令行、依赖库及其版本、错误信息原文、配置项键值。")
        appendLine("- 必须保留代码片段本身（若历史中出现过关键代码，摘录其核心部分而非描述它）。")
        appendLine("- 必须保留用户明确提出的约束、偏好、格式要求。")
        appendLine("- 不允许使用「讨论了某功能」「进行了一些修改」这类无信息量的表述。")
        appendLine("- 如果某字段在该段历史中没有内容，写「无」，不要省略该字段。")
        appendLine()
        appendLine("输出必须严格使用以下结构，每个标题都要出现：")
        appendLine()
        REQUIRED_SECTIONS.forEach { appendLine(it) }
    }.trimEnd()

    val user = buildString {
        append("这是对话历史的第 ")
        append(chunkIndex + 1).append('/').append(chunkTotal).append(" 段。\n")
        append("请按上述结构提炼这一段，不要遗漏任何技术细节。\n\n")
        append("===== 历史片段开始 =====\n")
        append(chunkText)
        append("\n===== 历史片段结束 =====")
    }

    return listOf(
        ChatMessage(role = Role.SYSTEM, content = system),
        ChatMessage(role = Role.USER, content = user)
    )
}

/** 构造最终收敛请求：把多个分块摘要合并成一份完整档案 */
internal fun buildFinalRequest(
    chunkSummaries: List<String>,
    additionalPrompt: String,
    keepRecentCount: Int,
    totalCompressed: Int
): List<ChatMessage> {
    val system = buildString {
        appendLine("你是一名严谨的技术档案整理员。你会收到若干段已经提炼过的技术摘要，")
        appendLine("需要把它们合并成一份完整、无冲突、可直接作为后续对话上下文的档案。")
        appendLine()
        appendLine("合并规则：")
        appendLine("- 相同信息去重，不同信息全部保留，冲突信息标注冲突点与最终结论。")
        appendLine("- 严禁做「二次概括」：不得把具体路径、方法名、代码片段抽象成描述性语句。")
        appendLine("- 严禁丢失任何文件路径、类名、方法名、变量名、依赖版本、报错原文。")
        appendLine("- 如果某字段在全部输入中都没有内容，写「无」，不要省略该字段。")
        appendLine("- 篇幅不设上限，信息完整优先于简洁。")
        appendLine()
        appendLine("输出必须严格使用以下结构，每个标题都要出现：")
        appendLine()
        REQUIRED_SECTIONS.forEach { appendLine(it) }
    }.trimEnd()

    val user = buildString {
        append("本次共压缩 ").append(totalCompressed).append(" 条历史消息，")
        append("已为其保留了最近 ").append(keepRecentCount)
        append(" 条原文（不在你收到的内容中）。\n")
        if (additionalPrompt.isNotBlank()) {
            append("用户额外要求：").append(additionalPrompt).append('\n')
        }
        append("\n以下是各分段的提炼结果：\n\n")
        chunkSummaries.forEachIndexed { i, s ->
            append("===== 第 ").append(i + 1).append(" 段提炼 =====\n")
            append(s).append('\n')
        }
        append("\n请合并为一份完整档案。")
    }

    return listOf(
        ChatMessage(role = Role.SYSTEM, content = system),
        ChatMessage(role = Role.USER, content = user)
    )
}

/**
 * 本地降级摘要：在模型接口不可用时使用。
 *
 * 不再生成「已压缩 N 条对话」这种无信息量的句子，
 * 而是尽量从原始消息中抽取结构化的客观事实：涉及的文件路径、工具调用记录、
 * 用户诉求、助手结论。虽然比不上模型摘要，但至少保留了可用的线索。
 */
internal fun buildLocalFallback(messages: List<Message>, keepRecentCount: Int): String {
    if (messages.isEmpty()) return "（无可压缩内容）"

    val sb = StringBuilder()

    // 1. 用户诉求：取前三条用户消息的原文片段
    val userMsgs = messages.filter { it.role == Role.USER }
    sb.append("【任务目标】\n")
    if (userMsgs.isEmpty()) {
        sb.append("无\n")
    } else {
        userMsgs.take(3).forEach { m ->
            sb.append("- ").append(m.content.take(300).trim()).append('\n')
        }
        if (userMsgs.size > 3) {
            sb.append("- ...（另有 ").append(userMsgs.size - 3).append(" 条用户消息）\n")
        }
    }

    // 2. 工具调用记录：这是「读过哪些文件」最可靠的来源
    val allInvocations = messages.flatMap { it.toolInvocations }
    sb.append("\n【已完成事项】\n")
    if (allInvocations.isEmpty()) {
        sb.append("历史中未记录工具调用\n")
    } else {
        allInvocations.groupBy { it.toolName }.forEach { (tool, invs) ->
            sb.append("- ").append(tool).append(" 共调用 ").append(invs.size).append(" 次\n")
        }
    }

    // 3. 从工具参数里抽取出现的文件路径，这是最关键的上下文线索
    val pathRegex = Regex("""/storage/[^\s"'\\,)\]]+|/data/[^\s"'\\,)\]]+""")
    val paths = LinkedHashSet<String>()
    allInvocations.forEach { inv ->
        pathRegex.findAll(inv.arguments).forEach { paths.add(it.value) }
    }
    sb.append("\n【关键文件与路径】\n")
    if (paths.isEmpty()) {
        sb.append("无\n")
    } else {
        paths.take(60).forEach { sb.append("- ").append(it).append('\n') }
        if (paths.size > 60) {
            sb.append("- ...（另有 ").append(paths.size - 60).append(" 个路径）\n")
        }
    }

    // 4. 工具调用的输出摘要：保留最近若干次有输出的调用开头
    sb.append("\n【代码与技术要点】\n")
    val lastOutputs = allInvocations.filter { it.result.isNotBlank() }.takeLast(8)
    if (lastOutputs.isEmpty()) {
        sb.append("无\n")
    } else {
        lastOutputs.forEach { inv ->
            sb.append("- [").append(inv.toolName).append("] ")
            sb.append(inv.result.take(400).replace('\n', ' ')).append('\n')
        }
    }

    // 5. 助手结论：最后两条助手消息的原文
    val assistantMsgs = messages.filter {
        it.role == Role.ASSISTANT && it.content.isNotBlank()
    }
    sb.append("\n【已确认的结论与决策】\n")
    if (assistantMsgs.isEmpty()) {
        sb.append("无\n")
    } else {
        assistantMsgs.takeLast(2).forEach { m ->
            sb.append("- ").append(m.content.take(600)).append('\n')
        }
    }

    sb.append("\n【待办与未解决问题】\n")
    sb.append("- 需要根据上述历史继续推进（本次为本地降级摘要，细节可能不完整）\n")

    sb.append("\n【约束与偏好】\n")
    sb.append("- 已保留最近 ").append(keepRecentCount).append(" 条原文\n")

    return sb.toString()
}
