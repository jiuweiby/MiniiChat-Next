package com.miniichatNext.carter.data.mcp

/**
 * 工具名归一化与容错匹配。
 *
 * 背景：不同来源的工具名会带上各种分隔符前缀，导致模型上下文与本地存储的名字对不上：
 *
 *   1. 第三方 MCP 客户端常把「服务器名 + 工具名」拼成一个扁平的全局工具名，
 *      用 __ / . / - 等作为分隔符（例如 revmcp-local__r2_cmd）。
 *   2. 模型在多轮对话中可能沿用历史消息里出现过的旧名字，
 *      而那个名字属于上一版本的注册方式。
 *   3. 某些网关会把工具名中的特殊字符替换或截断。
 *
 * 本对象提供两级归一化：
 *   - [normalized]：仅做字符级清理，用于「同名比对」。
 *   - [candidates]：按分隔符逐级剥离前缀，产出可用于反查的候选名序列。
 *
 * 设计原则：只产出候选，不做任何猜测性的自动执行。
 * 调用方必须用候选名能在真实工具表里命中才会采用，
 * 避免出现「工具名对不上时随便挑一个执行」这种危险行为。
 */
object ToolNameResolver {

    /** 常见的服务器-工具名分隔符，按长度从长到短尝试剥离 */
    private val SEPARATORS = listOf("__", "::", ".", "/", "-")

    /**
     * 去除首尾空白，并把内部连续空白压成单个下划线。
     *
     * 不做大小写转换：绝大多数 MCP 工具名区分大小写，擅自转小写会误伤。
     */
    fun normalized(raw: String): String =
        raw.trim().replace(Regex("\\s+"), "_")

    /**
     * 生成候选名序列，按「可信度从高到低」排列：
     *
     *   1. 原名（清理后）
     *   2. 依次剥离每个分隔符之前的所有片段，保留最后一段
     *      例：revmcp-local__r2_cmd -> r2_cmd
     *          mcp.revmcp.r2_cmd    -> revmcp.r2_cmd -> r2_cmd
     *   3. 对每一级剥离结果再去掉前导的下划线
     *
     * 结果保序去重，调用方按顺序尝试匹配即可。
     */
    fun candidates(raw: String): List<String> {
        val base = normalized(raw)
        if (base.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        out.add(base)

        // 逐级剥离：从最长分隔符开始，每次剥掉最前面一段
        var current = base
        var changed = true
        while (changed) {
            changed = false
            for (sep in SEPARATORS) {
                val idx = current.indexOf(sep)
                if (idx > 0 && idx + sep.length < current.length) {
                    val tail = current.substring(idx + sep.length).trimStart('_', '.', '-')
                    if (tail.isNotEmpty() && tail != current) {
                        out.add(tail)
                        current = tail
                        changed = true
                        break
                    }
                }
            }
        }

        // 兜底：把常见的分隔符全部替换成下划线后比对
        val flattened = base.replace(Regex("[.:/\\-]+"), "_").trim('_')
        if (flattened.isNotEmpty()) out.add(flattened)

        return out.toList()
    }

    /**
     * 在给定工具列表中按候选名顺序查找。
     *
     * @param rawName   模型给出的原始工具名，可能带前缀
     * @param available 本地实际持有的全部工具名（内置 + 外部）
     * @return 命中的真实工具名；全部候选都未命中时返回 null
     */
    fun resolve(rawName: String, available: Collection<String>): String? {
        val pool = available.toHashSet()
        for (candidate in candidates(rawName)) {
            if (candidate in pool) return candidate
        }
        return null
    }
}
