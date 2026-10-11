package com.miniichatNext.carter.ui.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.mcp.McpAggregateConfig
import com.miniichatNext.carter.data.mcp.McpAggregateServer
import com.miniichatNext.carter.data.mcp.McpAggregateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 聚合 MCP 服务器配置页。
 *
 * 开关打开后，本机所有工具（内置 + 已启用外部 MCP）会通过一个端点对外提供，
 * 其他 APP 只需以 Streamable HTTP 方式连接该端点即可调用。
 *
 * 所有 McpStore 读取都发生在服务器内部的后台线程，
 * 本页只读取 McpAggregateServer 暴露的纯内存缓存，绝不阻塞主线程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpAggregateScreen(
    aggregateStore: McpAggregateStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by aggregateStore.configFlow.collectAsState(initial = McpAggregateConfig())

    var portText by remember { mutableStateOf(config.port.toString()) }
    var running by remember { mutableStateOf(McpAggregateServer.isRunning()) }
    var toolNames by remember { mutableStateOf(McpAggregateServer.toolNamesCached()) }

    LaunchedEffect(config.port) { portText = config.port.toString() }

    // 定期从服务器缓存刷新展示信息：缓存本身由 worker 线程写入，这里是纯内存读取
    LaunchedEffect(config.enabled) {
        while (true) {
            running = McpAggregateServer.isRunning()
            toolNames = McpAggregateServer.toolNamesCached()
            delay(1200L)
        }
    }

    fun applyConfig(next: McpAggregateConfig) {
        val appContext = context.applicationContext
        scope.launch {
            aggregateStore.update { next }
            McpAggregateServer.apply(appContext, next)
            running = McpAggregateServer.isRunning()
            // 配置刚变，缓存刷新有短暂延迟，立刻再主动拉一次
            McpAggregateServer.refreshCacheAsync()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具聚合服务器") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "启用聚合服务器",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (running) "正在监听 · 已暴露 ${toolNames.size} 个工具"
                                else "已关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (running) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { checked ->
                                applyConfig(config.copy(enabled = checked))
                            }
                        )
                    }
                }
            }

            if (config.enabled) {
                item {
                    val endpoint = "http://" +
                        "${if (config.loopbackOnly) "127.0.0.1" else "0.0.0.0"}" +
                        ":${config.port}${config.endpointPath}"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "接入端点 (Streamable HTTP)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                endpoint,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    copyToClipboard(context, endpoint)
                                    Toast.makeText(context, "端点已复制", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("复制端点")
                                }
                            }
                            if (config.requireToken && config.token.isNotBlank()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text(
                                    "接入令牌",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    config.token,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = {
                                        copyToClipboard(context, config.token)
                                        Toast.makeText(context, "令牌已复制", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("复制令牌")
                                    }
                                    TextButton(onClick = {
                                        val fresh = aggregateStore.newToken()
                                        applyConfig(config.copy(token = fresh))
                                    }) {
                                        Text("重新生成")
                                    }
                                }
                                Text(
                                    "客户端需在请求头携带 Authorization: Bearer <令牌>",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    SettingSwitchCard(
                        title = "仅本机可访问",
                        subtitle = "开启后只监听 127.0.0.1，其他设备无法连接；" +
                            "关闭则监听全部网卡，同局域网设备可接入。",
                        checked = config.loopbackOnly,
                        onCheckedChange = { applyConfig(config.copy(loopbackOnly = it)) }
                    )
                }

                item {
                    SettingSwitchCard(
                        title = "暴露内置文件工具",
                        subtitle = "包含文件读写、复制、移动、删除、目录浏览、" +
                            "Shell 与 Python 执行等本地能力。",
                        checked = config.exposeBuiltIn,
                        onCheckedChange = { applyConfig(config.copy(exposeBuiltIn = it)) }
                    )
                }

                item {
                    SettingSwitchCard(
                        title = "暴露外部 MCP 工具",
                        subtitle = "把已启用的外部 MCP 服务器工具一并转发出去。" +
                            "同名工具会自动加前缀去重。",
                        checked = config.exposeExternal,
                        onCheckedChange = { applyConfig(config.copy(exposeExternal = it)) }
                    )
                }

                item {
                    SettingSwitchCard(
                        title = "要求接入令牌",
                        subtitle = "开启后客户端必须携带正确令牌才能调用，" +
                            "建议在非回环监听时启用。",
                        checked = config.requireToken,
                        onCheckedChange = { checked ->
                            val token = if (checked && config.token.isBlank()) {
                                aggregateStore.newToken()
                            } else {
                                config.token
                            }
                            applyConfig(config.copy(requireToken = checked, token = token))
                        }
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "监听端口",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "范围 ${McpAggregateStore.MIN_PORT} - ${McpAggregateStore.MAX_PORT}，" +
                                    "修改后立即重启监听。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = portText,
                                    onValueChange = { v ->
                                        if (v.length <= 5 && v.all { it.isDigit() }) portText = v
                                    },
                                    label = { Text("端口") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                val parsed = portText.toIntOrNull()
                                TextButton(
                                    enabled = parsed != null &&
                                        parsed in McpAggregateStore.MIN_PORT..McpAggregateStore.MAX_PORT &&
                                        parsed != config.port,
                                    onClick = {
                                        val p = portText.toIntOrNull() ?: return@TextButton
                                        applyConfig(config.copy(port = p))
                                    }
                                ) {
                                    Text("应用")
                                }
                            }
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
                            "对外暴露的工具",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "共 ${toolNames.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (toolNames.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Text(
                                "当前没有可暴露的工具，请检查上方开关或先添加外部 MCP 服务器。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                } else {
                    items(toolNames, key = { it }) { name ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mcp", text))
    }
}
