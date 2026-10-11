package com.miniichatNext.carter.data.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.aggregateDataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_aggregate")

/**
 * 聚合 MCP 服务器配置。
 *
 * 该服务器把本机 App 当前持有的全部工具（内置文件工具 + 所有已启用的外部 MCP 工具）
 * 聚合成单一 MCP 端点，供其他 APP / 桌面客户端接入使用。
 */
data class McpAggregateConfig(
    val enabled: Boolean = false,
    /** 监听端口，默认 8766，与 RevMCP 的 8765 错开 */
    val port: Int = 8766,
    /** 是否只监听回环地址（true 时仅本机可访问，false 时同局域网可访问） */
    val loopbackOnly: Boolean = true,
    /** 外部接入是否需要携带令牌 */
    val requireToken: Boolean = false,
    /** 接入令牌，为空时自动生成 */
    val token: String = "",
    /** 是否把内置文件工具一并暴露 */
    val exposeBuiltIn: Boolean = true,
    /** 是否把外部 MCP 服务器工具一并暴露（避免环形递归） */
    val exposeExternal: Boolean = true
) {
    val endpointPath: String get() = "/mcp"
    val endpoint: String get() = "http://127.0.0.1:$port$endpointPath"
}

class McpAggregateStore(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("agg_enabled")
        val PORT = intPreferencesKey("agg_port")
        val LOOPBACK = booleanPreferencesKey("agg_loopback_only")
        val REQUIRE_TOKEN = booleanPreferencesKey("agg_require_token")
        val TOKEN = stringPreferencesKey("agg_token")
        val EXPOSE_BUILTIN = booleanPreferencesKey("agg_expose_builtin")
        val EXPOSE_EXTERNAL = booleanPreferencesKey("agg_expose_external")
    }

    val configFlow: Flow<McpAggregateConfig> = context.aggregateDataStore.data.map { p ->
        McpAggregateConfig(
            enabled = p[Keys.ENABLED] ?: false,
            port = (p[Keys.PORT] ?: DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT),
            loopbackOnly = p[Keys.LOOPBACK] ?: true,
            requireToken = p[Keys.REQUIRE_TOKEN] ?: false,
            token = p[Keys.TOKEN] ?: "",
            exposeBuiltIn = p[Keys.EXPOSE_BUILTIN] ?: true,
            exposeExternal = p[Keys.EXPOSE_EXTERNAL] ?: true
        )
    }

    suspend fun snapshot(): McpAggregateConfig = configFlow.first()

    suspend fun update(transform: (McpAggregateConfig) -> McpAggregateConfig) {
        context.aggregateDataStore.edit { p ->
            val cur = McpAggregateConfig(
                enabled = p[Keys.ENABLED] ?: false,
                port = (p[Keys.PORT] ?: DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT),
                loopbackOnly = p[Keys.LOOPBACK] ?: true,
                requireToken = p[Keys.REQUIRE_TOKEN] ?: false,
                token = p[Keys.TOKEN] ?: "",
                exposeBuiltIn = p[Keys.EXPOSE_BUILTIN] ?: true,
                exposeExternal = p[Keys.EXPOSE_EXTERNAL] ?: true
            )
            val next = transform(cur)
            p[Keys.ENABLED] = next.enabled
            p[Keys.PORT] = next.port.coerceIn(MIN_PORT, MAX_PORT)
            p[Keys.LOOPBACK] = next.loopbackOnly
            p[Keys.REQUIRE_TOKEN] = next.requireToken
            p[Keys.TOKEN] = next.token
            p[Keys.EXPOSE_BUILTIN] = next.exposeBuiltIn
            p[Keys.EXPOSE_EXTERNAL] = next.exposeExternal
        }
    }

    /** 生成一个随机接入令牌（32 位十六进制） */
    fun newToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_PORT = 8766
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
