package com.miniichatNext.carter

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.mcp.McpAggregateServer
import com.miniichatNext.carter.data.mcp.McpAggregateStore
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.mcp.RevMcpHost
import com.miniichatNext.carter.debug.CrashLogger
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiniiChatApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DebugLog.restore(this)
        DebugLog.i("App", "MiniiChatApplication.onCreate")
        CrashLogger.install(this)

        BuiltInFileTools.init(filesDir)

        initPythonRuntime()

        // 启动 RevMCP 内嵌本地逆向分析 MCP 服务（127.0.0.1:8765/mcp）
        // 并自动注册到 MCP 服务器列表，全部工具自动进入 AI 可用工具清单
        RevMcpHost.init(this)

        // 初始化聚合 MCP 服务器宿主，并按其持久化配置决定是否监听
        McpAggregateServer.init(this)
        appScope.launch {
            runCatching {
                val config = McpAggregateStore(this@MiniiChatApplication).snapshot()
                McpAggregateServer.apply(this@MiniiChatApplication, config)
                DebugLog.i(
                    "App",
                    "聚合 MCP 服务: enabled=${config.enabled} port=${config.port} " +
                        "loopback=${config.loopbackOnly} token=${config.requireToken}"
                )
            }.onFailure {
                DebugLog.e("App", "聚合 MCP 服务初始化失败: ${it.message}", it)
            }
        }

        // MCP 数据一致性诊断。
        //
        // 这里刻意只读不写。
        //
        // 历史教训：早前版本在 Application.onCreate 里无条件执行
        // repairOrphanToolOwnership / purgeEmbeddedBuiltIns / purgeOrphanTools，
        // 结果把用户已配置好的工具记录当成「脏数据」删掉，造成工具大面积失效。
        // 启动阶段是最不适合改数据的时机：
        //   - RevMcpHost 正在后台并发拉取工具，读—改—写序列会互相覆盖
        //   - 用户无法预期启动会改变自己的配置
        //
        // 现在的策略：
        //   - 启动只做诊断，把发现的问题完整写进日志
        //   - 真正的整理由用户在 MCP 管理页手动触发 compactUserData()
        //   - 执行期的悬空引用由 executeTool 的错误提示引导用户处理
        appScope.launch {
            runCatching {
                val report = McpStore(this@MiniiChatApplication).diagnose()
                DebugLog.i("McpStore", report)
            }.onFailure {
                DebugLog.w("App", "MCP 数据诊断失败: ${it.message}")
            }
            // 诊断完成后刷新聚合服务器缓存，保证对外清单与内部一致
            McpAggregateServer.refreshCacheAsync()
        }
    }

    private fun initPythonRuntime() {
        runCatching {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
                DebugLog.i("App", "Chaquopy Python 运行时初始化成功")
            }
        }.onFailure {
            DebugLog.e("App", "Chaquopy 初始化异常: ${it.message}", it)
        }
    }
}
