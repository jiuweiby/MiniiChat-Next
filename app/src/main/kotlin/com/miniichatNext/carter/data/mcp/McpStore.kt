package com.miniichatNext.carter.data.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.mcpDataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_configs")

/**
 * 工具记录的身份标识：用 name + serverId 组合。
 *
 * 定义为文件级私有扩展函数而不是 McpStore 的成员扩展函数：
 * 成员位置上的扩展函数会让 `this` 同时存在两个候选接收者（McpStore 与 McpTool），
 * 属性名一旦与 McpStore 的成员撞名就会静默解析到错误的对象。
 * 放在顶层可以让接收者唯一确定。
 */
private fun McpTool.identity(): String = "$name@$serverId"

class McpStore(private val context: Context) {

    private val serversKey = stringPreferencesKey("mcp_servers_json")
    private val toolsKey = stringPreferencesKey("mcp_tools_json")
    private val permsKey = stringPreferencesKey("mcp_perms_json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    val serversFlow: Flow<List<McpServerConfig>> =
        context.mcpDataStore.data.map { prefs ->
            val raw = prefs[serversKey]
            if (raw.isNullOrBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    val toolsFlow: Flow<List<McpTool>> =
        context.mcpDataStore.data.map { prefs ->
            val raw = prefs[toolsKey]
            if (raw.isNullOrBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(McpTool.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    val permsFlow: Flow<List<McpToolPermission>> =
        context.mcpDataStore.data.map { prefs ->
            val raw = prefs[permsKey]
            if (raw.isNullOrBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(McpToolPermission.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    suspend fun snapshot(): List<McpServerConfig> = serversFlow.first()
    suspend fun toolsSnapshot(): List<McpTool> = toolsFlow.first()
    suspend fun permsSnapshot(): List<McpToolPermission> = permsFlow.first()

    suspend fun saveServers(servers: List<McpServerConfig>) {
        context.mcpDataStore.edit { prefs ->
            prefs[serversKey] =
                json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers)
        }
    }

    suspend fun saveTools(tools: List<McpTool>) {
        context.mcpDataStore.edit { prefs ->
            prefs[toolsKey] =
                json.encodeToString(ListSerializer(McpTool.serializer()), tools)
        }
    }

    suspend fun savePerms(perms: List<McpToolPermission>) {
        context.mcpDataStore.edit { prefs ->
            prefs[permsKey] =
                json.encodeToString(ListSerializer(McpToolPermission.serializer()), perms)
        }
    }

    suspend fun upsertServer(server: McpServerConfig) {
        val cur = snapshot().toMutableList()
        val idx = cur.indexOfFirst { it.id == server.id }
        if (idx >= 0) cur[idx] = server else cur.add(server)
        saveServers(cur)
    }

    suspend fun deleteServer(id: String) {
        saveServers(snapshot().filterNot { it.id == id })
        saveTools(toolsSnapshot().filterNot { it.serverId == id })
        savePerms(permsSnapshot().filterNot { it.serverId == id })
    }

    /**
     * 用某个服务器最新拉取到的工具列表替换其旧记录。
     *
     * 强制给每条工具打上 serverId 并清除 builtIn 标记：
     * 上层所有归属判断都基于 tool.serverId == server.id，一旦为空就等同于工具不存在。
     */
    suspend fun replaceToolsForServer(serverId: String, tools: List<McpTool>) {
        val cur = toolsSnapshot().filterNot { it.serverId == serverId }.toMutableList()
        cur.addAll(tools.map { it.copy(serverId = serverId, builtIn = false) })
        saveTools(cur)
    }

    // ---------------------------------------------------------------
    // 权限查询
    // ---------------------------------------------------------------

    /**
     * 查询某个工具的权限记录。
     *
     * [permissionScopeId] 是权限记录里 serverId 字段的取值：
     *   - 内置工具传 [BuiltInFileTools.BUILTIN_PERMISSION_ID]（"builtin"）
     *   - 外部工具传它所属服务器的 id
     *
     * ## 为什么必须带 scope 精确定位
     *
     * 旧实现对任何外部工具都只用 toolName 匹配：
     *
     *     all.find { it.toolName == toolName && it.serverId.isNotBlank() }
     *
     * 两台服务器提供同名工具时（例如都暴露一个 read 工具），
     * 给 A 配的审批策略会被 B 命中，用户在界面上怎么改都改不动 B 的行为。
     *
     * ## 兼容早期写法
     *
     * 内置工具的权限记录在不同版本里可能写在 "builtin" 或空串下，两种都认。
     */
    suspend fun findPermission(
        toolName: String,
        permissionScopeId: String
    ): McpToolPermission? {
        val all = permsSnapshot()
        if (permissionScopeId == BuiltInFileTools.BUILTIN_PERMISSION_ID) {
            return all.find {
                it.toolName == toolName && it.serverId == BuiltInFileTools.BUILTIN_PERMISSION_ID
            } ?: all.find { it.toolName == toolName && it.serverId.isBlank() }
        }
        return all.find { it.toolName == toolName && it.serverId == permissionScopeId }
    }

    suspend fun setToolPermission(
        serverId: String,
        toolName: String,
        enabled: Boolean,
        requireApproval: Boolean
    ) {
        val cur = permsSnapshot().toMutableList()
        val idx = cur.indexOfFirst { it.serverId == serverId && it.toolName == toolName }
        val newPerm = McpToolPermission(
            serverId = serverId,
            toolName = toolName,
            enabled = enabled,
            requireApproval = requireApproval
        )
        if (idx >= 0) cur[idx] = newPerm else cur.add(newPerm)
        savePerms(cur)
    }

    suspend fun exportFullJson(): String {
        val backup = McpBackupPayload(
            servers = snapshot(),
            tools = toolsSnapshot(),
            perms = permsSnapshot()
        )
        return json.encodeToString(McpBackupPayload.serializer(), backup)
    }

    suspend fun importFullJson(raw: String) {
        runCatching {
            val payload = json.decodeFromString(McpBackupPayload.serializer(), raw)
            saveServers(payload.servers)
            // 导入时过滤掉内置工具，它们不该来自备份
            saveTools(payload.tools.filterNot { it.builtIn })
            savePerms(payload.perms)
        }
    }

    // ---------------------------------------------------------------
    // 诊断与维护
    //
    // 以下两个方法都只做「读 + 记录」，或由用户显式触发才写。
    // 不在 Application.onCreate 里自动执行。
    //
    // 历史教训：早期版本在启动时无条件清理数据，把用户已配置好的工具记录
    // 当成「脏数据」删掉，造成工具大面积失效。启动阶段也不适合改数据：
    // RevMcpHost 正在后台并发拉取工具，读—改—写序列会互相覆盖。
    // ---------------------------------------------------------------

    /**
     * 生成一份数据一致性诊断报告，供 UI 展示。
     */
    suspend fun diagnose(): String {
        val servers = snapshot()
        val tools = toolsSnapshot()
        val serverIds = servers.map { it.id }.toSet()

        val noOwner = tools.filter { it.serverId.isBlank() && !it.builtIn }
        val dangling = tools.filter { it.serverId.isNotBlank() && it.serverId !in serverIds }
        val embeddedBuiltIns = tools.filter { it.builtIn }
        val enabledServers = servers.filter { it.enabled }
        val emptyEnabled = enabledServers.filter { s -> tools.none { it.serverId == s.id } }

        return buildString {
            append("服务器总数 ").append(servers.size)
            append("（启用 ").append(enabledServers.size).append("）\n")
            append("工具记录总数 ").append(tools.size).append('\n')
            if (noOwner.isNotEmpty()) {
                append("· 缺少归属 ").append(noOwner.size).append(" 条：")
                append(noOwner.joinToString("、") { it.name }).append('\n')
            }
            if (dangling.isNotEmpty()) {
                append("· 悬空引用 ").append(dangling.size).append(" 条：")
                append(
                    dangling.joinToString("、") { "${it.name}@${it.serverId}" }
                ).append('\n')
            }
            if (embeddedBuiltIns.isNotEmpty()) {
                append("· 内置工具落盘副本 ").append(embeddedBuiltIns.size).append(" 条：")
                append(embeddedBuiltIns.joinToString("、") { it.name }).append('\n')
            }
            if (emptyEnabled.isNotEmpty()) {
                append("· 已启用但无工具记录的服务器：")
                append(emptyEnabled.joinToString("、") { it.name }).append('\n')
            }
            if (noOwner.isEmpty() && dangling.isEmpty() &&
                embeddedBuiltIns.isEmpty() && emptyEnabled.isEmpty()
            ) {
                append("未发现异常")
            }
        }
    }

    /**
     * 用户手动触发的数据整理。
     *
     * 执行内容：
     *   1. 删除内置工具的落盘副本（唯一真相来源是内存常量）
     *   2. 删除指向已删除服务器的悬空工具记录
     *
     * 刻意不做的事：
     *   - 不动 serverId 为空的外部工具。它们仍有被修复的可能，
     *     直接删除会让用户白配一遍。
     */
    suspend fun compactUserData(): String {
        val servers = snapshot()
        val tools = toolsSnapshot()
        val serverIds = servers.map { it.id }.toSet()

        val embedded = tools.filter { it.builtIn }
        val dangling = tools.filter { it.serverId.isNotBlank() && it.serverId !in serverIds }
        val toRemove = (embedded + dangling).map { it.identity() }.toSet()

        if (toRemove.isEmpty()) {
            return "数据结构已是最简状态，无需整理"
        }

        if (embedded.isNotEmpty()) {
            DebugLog.i(
                "McpStore",
                "整理：删除内置工具落盘副本 ${embedded.size} 条 -> " +
                    embedded.joinToString("、") { it.name }
            )
        }
        if (dangling.isNotEmpty()) {
            DebugLog.i(
                "McpStore",
                "整理：删除悬空工具记录 ${dangling.size} 条 -> " +
                    dangling.joinToString("、") { "${it.name}@${it.serverId}" }
            )
        }

        saveTools(tools.filterNot { it.identity() in toRemove })
        return "已整理：移除内置工具副本 ${embedded.size} 条，" +
            "悬空工具记录 ${dangling.size} 条"
    }
}

@kotlinx.serialization.Serializable
private data class McpBackupPayload(
    val servers: List<McpServerConfig> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val perms: List<McpToolPermission> = emptyList()
)
