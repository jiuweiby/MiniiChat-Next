package com.miniichatNext.carter.ui.mcp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.mcp.McpAggregateServer
import com.miniichatNext.carter.data.mcp.McpAggregateStore
import com.miniichatNext.carter.data.mcp.McpClient
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.data.mcp.McpTransport
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.launch

/**
 * 内置工具权限使用的归属 id。
 *
 * 直接引用 [BuiltInFileTools.BUILTIN_PERMISSION_ID]，不再本地重新定义常量：
 * 执行层的权限匹配、设置页的权限写入、以及这里读取权限，三处必须使用同一个值，
 * 任何一处写了不同的字符串都会导致用户配置看起来"保存了但不生效"。
 */
private const val BUILTIN_SERVER_ID = BuiltInFileTools.BUILTIN_PERMISSION_ID

/**
 * 未配置过的工具的默认权限。
 *
 * 这两个默认值必须与 [McpToolPermission] 数据类、以及执行层的判定完全一致，
 * 否则会出现「界面上看到的是关的，实际却仍要审批」这类不一致。
 *
 *   enabled = true           默认启用，工具直接进入 AI 的可用清单
 *   requireApproval = false  调用自动执行，无需逐次确认
 *
 * 用户的操作路径：
 *   1. 工具默认可用、默认免审批；
 *   2. 想限制某个工具时，关闭主开关（AI 不再看到它）；
 *   3. 想让某工具恢复逐次确认时，再打开「调用时需要用户审批」开关。
 */
private fun defaultPermission(toolName: String, scopeId: String) = McpToolPermission(
    toolName = toolName,
    serverId = scopeId,
    enabled = true,
    requireApproval = false
)

/**
 * MCP 管理总入口。
 *
 * 三个子页面之间用互斥状态切换，避免额外引入 Navigation 依赖：
 *  - 服务器列表（默认）
 *  - 内置工具配置
 *  - 聚合服务器配置
 */
@Composable
fun McpManageScreen(
    mcpStore: McpStore,
    aggregateStore: McpAggregateStore,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit
) {
    var subPage by remember { mutableStateOf(McpSubPage.LIST) }
    when (subPage) {
        McpSubPage.LIST -> McpServersScreen(
            mcpStore = mcpStore,
            aggregateStore = aggregateStore,
            onBack = onBack,
            onOpenServerDetail = onOpenDetail,
            onOpenBuiltInTools = { subPage = McpSubPage.BUILT_IN },
            onOpenAggregate = { subPage = McpSubPage.AGGREGATE }
        )
        McpSubPage.BUILT_IN -> BuiltInToolsScreen(
            mcpStore = mcpStore,
            onBack = { subPage = McpSubPage.LIST }
        )
        McpSubPage.AGGREGATE -> McpAggregateScreen(
            aggregateStore = aggregateStore,
            onBack = { subPage = McpSubPage.LIST }
        )
    }
}

private enum class McpSubPage { LIST, BUILT_IN, AGGREGATE }

/**
 * MCP 服务器管理主列表页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    mcpStore: McpStore,
    aggregateStore: McpAggregateStore,
    onBack: () -> Unit,
    onOpenServerDetail: (String) -> Unit,
    onOpenBuiltInTools: () -> Unit,
    onOpenAggregate: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by mcpStore.serversFlow.collectAsState(initial = emptyList())
    val tools by mcpStore.toolsFlow.collectAsState(initial = emptyList())
    val aggregateConfig by aggregateStore.configFlow.collectAsState(initial = null)

    var showAddDialog by remember { mutableStateOf(false) }

    // 聚合服务器的暴露工具数从纯内存缓存读取，避免在主线程触碰 DataStore。
    // 缓存由 McpAggregateServer 的 worker 线程负责刷新。
    var aggregateToolCount by remember { mutableStateOf(McpAggregateServer.toolCountCached()) }
    LaunchedEffect(aggregateConfig?.enabled) {
        aggregateToolCount = McpAggregateServer.toolCountCached()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 工具服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加 MCP 服务器")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                BuiltInToolsEntryCard(
                    builtInToolsCount = BuiltInFileTools.schema.size,
                    onClick = onOpenBuiltInTools
                )
            }

            item {
                AggregateEntryCard(
                    enabled = aggregateConfig?.enabled == true,
                    port = aggregateConfig?.port ?: McpAggregateStore.DEFAULT_PORT,
                    toolCount = if (aggregateConfig?.enabled == true) aggregateToolCount else 0,
                    onClick = onOpenAggregate
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "外部 MCP 服务器",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "共 ${servers.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (servers.isEmpty()) {
                item {
                    EmptyMcpServersCard(onAdd = { showAddDialog = true })
                }
            } else {
                items(servers, key = { it.id }) { server ->
                    val serverTools = tools.filter { it.serverId == server.id }
                    McpServerItemCard(
                        server = server,
                        toolCount = serverTools.size,
                        onClick = { onOpenServerDetail(server.id) },
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                mcpStore.upsertServer(server.copy(enabled = enabled))
                                // 下游启用状态变化会影响聚合清单，触发一次缓存刷新
                                McpAggregateServer.refreshCacheAsync()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                mcpStore.deleteServer(server.id)
                                McpAggregateServer.refreshCacheAsync()
                                Toast.makeText(
                                    context, "已删除 MCP 服务器", Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, transport, headers ->
                scope.launch {
                    val newServer = McpServerConfig(
                        id = newId(),
                        name = name,
                        url = url,
                        transport = transport.name,
                        headers = headers,
                        enabled = true
                    )
                    mcpStore.upsertServer(newServer)
                    showAddDialog = false
                    Toast.makeText(
                        context, "已添加服务器，正在获取工具列表...", Toast.LENGTH_SHORT
                    ).show()

                    // 立即向 MCP 服务器请求完整工具列表
                    runCatching {
                        val client = McpClient()
                        val fetchedTools = client.listTools(newServer)
                        mcpStore.replaceToolsForServer(newServer.id, fetchedTools)
                        McpAggregateServer.refreshCacheAsync()
                        Toast.makeText(
                            context,
                            "成功同步 ${fetchedTools.size} 个工具，" +
                                "默认全部开启且免审批，AI 可直接调用",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            context,
                            "获取工具列表失败: ${err.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }
}

/**
 * 聚合服务器入口卡片
 */
@Composable
private fun AggregateEntryCard(
    enabled: Boolean,
    port: Int,
    toolCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "工具聚合服务器",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (enabled) {
                        "运行中 · 端口 $port · 对外提供 $toolCount 个工具"
                    } else {
                        "已关闭 · 开启后可将本机全部工具提供给其他 APP 调用"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "配置",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * 内置工具入口卡片
 */
@Composable
private fun BuiltInToolsEntryCard(
    builtInToolsCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "内置文件工具",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "整文件读写、目录浏览、搜索、复制移动删除，" +
                        "以及 Shell / Python / DEX 逆向，共 $builtInToolsCount 个工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "管理",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * 空状态展示
 */
@Composable
private fun EmptyMcpServersCard(onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "暂无 MCP 服务器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "支持 Streamable HTTP 与旧版 SSE 标准协议。" +
                    "添加 MCP 服务器后即可赋予 AI 读取数据库、网络检索或外部 API 的能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加 MCP 服务器")
            }
        }
    }
}

/**
 * 单个 MCP 服务器列表卡片
 */
@Composable
private fun McpServerItemCard(
    server: McpServerConfig,
    toolCount: Int,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (server.enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = if (server.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val proto = if (server.tp() == McpTransport.STREAMABLE_HTTP) "HTTP" else "SSE"
                Text(
                    text = "$proto · $toolCount 个工具 · ${server.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = server.enabled,
                onCheckedChange = onToggleEnabled
            )

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 内置工具独立管理页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltInToolsScreen(
    mcpStore: McpStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val perms by mcpStore.permsFlow.collectAsState(initial = emptyList())
    val allTools = remember { BuiltInFileTools.schema }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内置工具配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "所有工具默认开启，AI 可直接调用。" +
                                "如需限制某个工具，关闭其主开关即可；" +
                                "如需让某个工具每次调用前都等您点【同意】，打开「调用时需要用户审批」开关。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(allTools, key = { it.name }) { tool ->
                // 权限记录可能写在 "builtin" 或空串下（早期版本差异），两种都认
                val perm = perms.find {
                    it.toolName == tool.name &&
                        (it.serverId == BUILTIN_SERVER_ID || it.serverId.isBlank())
                } ?: defaultPermission(tool.name, BUILTIN_SERVER_ID)

                ToolPermissionCard(
                    name = tool.name,
                    description = tool.description,
                    enabled = perm.enabled,
                    requiresApproval = perm.requireApproval,
                    onToggleEnabled = { enabled ->
                        scope.launch {
                            mcpStore.setToolPermission(
                                toolName = tool.name,
                                serverId = BUILTIN_SERVER_ID,
                                enabled = enabled,
                                requireApproval = perm.requireApproval
                            )
                        }
                    },
                    onToggleApproval = { reqApproval ->
                        scope.launch {
                            mcpStore.setToolPermission(
                                toolName = tool.name,
                                serverId = BUILTIN_SERVER_ID,
                                enabled = perm.enabled,
                                requireApproval = reqApproval
                            )
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 单个外部 MCP 服务器工具详情与权限管理页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerDetailScreen(
    serverId: String,
    mcpStore: McpStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by mcpStore.serversFlow.collectAsState(initial = emptyList())
    val tools by mcpStore.toolsFlow.collectAsState(initial = emptyList())
    val perms by mcpStore.permsFlow.collectAsState(initial = emptyList())

    val server = servers.find { it.id == serverId }
    val serverTools = tools.filter { it.serverId == serverId }
    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server?.name ?: "服务器工具列表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (server != null) {
                                scope.launch {
                                    isRefreshing = true
                                    runCatching {
                                        val client = McpClient()
                                        val list = client.listTools(server)
                                        mcpStore.replaceToolsForServer(server.id, list)
                                        McpAggregateServer.refreshCacheAsync()
                                        Toast.makeText(
                                            context,
                                            "已刷新工具列表，共 ${list.size} 个工具",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            "刷新失败: ${err.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    isRefreshing = false
                                }
                            }
                        }
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新工具列表")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (server == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("服务器不存在或已被移除")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "URL: ${server.url}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val proto = if (server.tp() == McpTransport.STREAMABLE_HTTP) {
                            "Streamable HTTP"
                        } else {
                            "SSE"
                        }
                        Text(
                            text = "协议: $proto",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "所有工具默认开启且免审批，AI 可直接调用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "该服务提供的工具",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "共 ${serverTools.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (serverTools.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "未发现可用工具，请点击右上角刷新按钮重新拉取",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                items(serverTools, key = { it.name }) { tool ->
                    val perm = perms.find {
                        it.serverId == server.id && it.toolName == tool.name
                    } ?: defaultPermission(tool.name, server.id)

                    ToolPermissionCard(
                        name = tool.name,
                        description = tool.description,
                        enabled = perm.enabled,
                        requiresApproval = perm.requireApproval,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                mcpStore.setToolPermission(
                                    toolName = tool.name,
                                    serverId = server.id,
                                    enabled = enabled,
                                    requireApproval = perm.requireApproval
                                )
                                McpAggregateServer.refreshCacheAsync()
                            }
                        },
                        onToggleApproval = { reqApproval ->
                            scope.launch {
                                mcpStore.setToolPermission(
                                    toolName = tool.name,
                                    serverId = server.id,
                                    enabled = perm.enabled,
                                    requireApproval = reqApproval
                                )
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 单个工具权限与审批配置卡片。
 *
 * 主开关（顶部 Switch）控制工具是否可用；审批开关（底部 Switch）控制
 * 可用之后每次调用是否需要用户确认。审批开关在主开关关闭时不可操作，
 * 因为它此时没有任何实际含义。
 */
@Composable
private fun ToolPermissionCard(
    name: String,
    description: String,
    enabled: Boolean,
    requiresApproval: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleApproval: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when {
                            !enabled -> MaterialTheme.colorScheme.outline
                            requiresApproval -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (enabled) "调用时需要用户审批" else "工具未启用，审批设置暂不生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                }

                Switch(
                    checked = requiresApproval,
                    onCheckedChange = onToggleApproval,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    }
}

/**
 * 添加 MCP 服务器对话框
 */
@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, transport: McpTransport, headers: Map<String, String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.STREAMABLE_HTTP) }
    var headerAuth by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务器") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("服务器名称") },
                    placeholder = { Text("例如：本地工具服务") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务 URL 地址") },
                    placeholder = { Text("https:// 或 http://") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "传输协议模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = transport == McpTransport.STREAMABLE_HTTP,
                        onClick = { transport = McpTransport.STREAMABLE_HTTP }
                    )
                    Text(
                        text = "Streamable HTTP (推荐)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { transport = McpTransport.STREAMABLE_HTTP }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = transport == McpTransport.SSE,
                        onClick = { transport = McpTransport.SSE }
                    )
                    Text(
                        text = "旧版 SSE (Server-Sent Events)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { transport = McpTransport.SSE }
                    )
                }

                OutlinedTextField(
                    value = headerAuth,
                    onValueChange = { headerAuth = it },
                    label = { Text("Authorization Header (可选)") },
                    placeholder = { Text("Bearer sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        val headers = mutableMapOf<String, String>()
                        if (headerAuth.isNotBlank()) {
                            headers["Authorization"] = headerAuth.trim()
                        }
                        onAdd(name.trim(), url.trim(), transport, headers)
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("添加并连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
