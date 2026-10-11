package com.miniichatNext.carter.data.mcp

import kotlinx.serialization.Serializable

/**
 * MCP 传输类型
 *
 *  STREAMABLE_HTTP : 新版 Streamable HTTP（单端点 POST，可返回 SSE 流）
 *  SSE             : 旧版 SSE（先 GET 拿到 endpoint event，再 POST 到该 endpoint）
 */
enum class McpTransport { STREAMABLE_HTTP, SSE }

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** STREAMABLE_HTTP / SSE */
    val transport: String = McpTransport.STREAMABLE_HTTP.name,
    val enabled: Boolean = true,
    /**
     * 服务器级审批开关。
     *
     * 注意：当前版本未在任何执行路径读取此字段。
     * 真正的审批判定由 [McpToolPermission.requireApproval] 逐工具决定。
     * 保留它只为兼容历史备份数据的反序列化，不要依赖它控制行为。
     */
    val requireApproval: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    /** 是否已成功拉取过工具列表 */
    val toolsFetched: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun tp(): McpTransport = runCatching { McpTransport.valueOf(transport) }
        .getOrDefault(McpTransport.STREAMABLE_HTTP)
}

/**
 * 一个已发现的 MCP 工具
 *
 * [serverId] 为空表示它不属于任何外部服务器（内置工具即为这种形态）
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: String = "",
    val serverId: String = "",
    /** 内置工具标记，用于 UI 分组与不可删除 */
    val builtIn: Boolean = false
)

/**
 * 单个工具的运行时权限。
 *
 * ## 两个开关职责完全不同
 *
 *   enabled          主开关：这个工具允不允许 AI 用
 *   requireApproval  审批开关：允许用之后，每次调用要不要用户点【同意】
 *
 * 行为组合如下：
 *
 *   没有权限记录           -> 按默认值处理：注入，且调用自动执行
 *   enabled=false          -> 不注入，模型看不到它
 *   enabled=true, approval=true    -> 注入，调用时弹卡片等用户确认
 *   enabled=true, approval=false   -> 注入，调用时自动执行
 *
 * ## 默认值为什么是「全开 + 免审批」
 *
 * [enabled] 默认 true：没有任何权限记录的工具一律可用。
 * 模型随时可以调用全部工具，这是「默认全部开启」的实现方式。
 *
 * [requireApproval] 默认 false：调用工具不再逐次弹卡片等用户确认，
 * 由执行层直接放行自动执行。
 *
 * 唯一例外是内置的 interactive_clarification（需求澄清工具）：
 * 它始终需要用户本人作答，不受本默认值影响。
 *
 * ## 三处默认值必须一致
 *
 *   1. 本数据类
 *   2. MCP 管理页展示未配置工具时的兜底值
 *   3. ChatViewModelMessage 里 collectAvailableTools / checkAutoApproveTools 的判定
 *
 * 历史教训：曾经执行层与 UI 用不同的默认值，
 * 用户在界面上看到开关是关的，实际每次调用却仍然被拦下来等待审批。
 */
@Serializable
data class McpToolPermission(
    val toolName: String,
    val serverId: String = "",
    val enabled: Boolean = true,
    val requireApproval: Boolean = false
)

/** 等待用户审批的一次工具调用 */
data class McpPendingApproval(
    val requestId: String,
    val serverId: String,
    val toolName: String,
    /** 传给工具的参数（原始 JSON 字符串） */
    val arguments: String,
    /** 展示给用户看的参数预览 */
    val preview: String
)

/** 工具调用执行结果 */
data class McpToolResult(
    val ok: Boolean,
    val content: String,
    val error: String? = null
)
