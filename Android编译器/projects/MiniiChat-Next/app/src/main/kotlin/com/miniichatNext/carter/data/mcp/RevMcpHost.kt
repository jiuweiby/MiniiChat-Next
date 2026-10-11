package com.miniichatNext.carter.data.mcp

import android.content.Context
import com.miniichatNext.carter.debug.DebugLog
import com.rev.mcptool.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * RevMCP 内嵌本地逆向分析服务器宿主
 */
object RevMcpHost {

    private const val TAG = "RevMcpHost"

    const val PORT = 8765
    const val SERVER_ID = "revmcp-local"
    const val SERVER_NAME = "RevMCP 本地逆向分析"
    const val ENDPOINT = "http://127.0.0.1:$PORT/mcp"

    private var server: McpServer? = null
    private var started = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(appContext: Context) {
        if (started) return
        started = true
        val ctx = appContext.applicationContext

        runCatching {
            val srv = McpServer(ctx, PORT, object : McpServer.Listener {
                override fun onProgress(pct: Int, msg: String) {
                    DebugLog.d(TAG, "progress $pct% $msg")
                }
                override fun onReady(port: Int) {
                    DebugLog.i(TAG, "RevMCP 内嵌服务已就绪: http://127.0.0.1:$port/mcp")
                }
                override fun onError(err: String) {
                    DebugLog.e(TAG, "RevMCP 服务错误: $err")
                }
            })
            server = srv
            Thread({
                runCatching { srv.run() }
                    .onFailure { DebugLog.e(TAG, "RevMCP 服务线程退出: ${it.message}", it) }
            }, "revmcp-server").apply {
                isDaemon = true
                start()
            }
        }.onFailure {
            DebugLog.e(TAG, "RevMCP 服务启动失败: ${it.message}", it)
            started = false
            return
        }

        scope.launch {
            runCatching { ensureRegistered(ctx) }
                .onFailure { DebugLog.e(TAG, "RevMCP 注册失败: ${it.message}", it) }
        }
    }

    private suspend fun ensureRegistered(ctx: Context) {
        val store = McpStore(ctx)
        val existing = store.snapshot().firstOrNull { it.id == SERVER_ID }
        val config = existing ?: McpServerConfig(
            id = SERVER_ID,
            name = SERVER_NAME,
            url = ENDPOINT,
            transport = McpTransport.STREAMABLE_HTTP.name,
            enabled = true,
            requireApproval = true
        )
        if (existing == null) {
            store.upsertServer(config)
            DebugLog.i(TAG, "已注册内嵌 RevMCP 服务器: $ENDPOINT")
        }

        runCatching {
            val tools = McpClient().listTools(config)
            if (tools.isNotEmpty()) {
                store.replaceToolsForServer(SERVER_ID, tools.map { it.copy(serverId = SERVER_ID) })
                DebugLog.i(TAG, "RevMCP 工具发现成功，共 ${tools.size} 个工具")
            }
        }.onFailure {
            DebugLog.w(TAG, "RevMCP 工具发现暂未成功: ${it.message}")
        }
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        started = false
        DebugLog.i(TAG, "RevMCP 内嵌服务已停止")
    }

    fun isRunning(): Boolean = server?.isRunning == true
}
