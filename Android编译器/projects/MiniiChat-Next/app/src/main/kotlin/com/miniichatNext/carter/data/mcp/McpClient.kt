package com.miniichatNext.carter.data.mcp

import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP JSON-RPC 2.0 客户端（纯 JDK HttpURLConnection 实现，不依赖 Ktor/OkHttp）。
 *
 * 支持两种传输：
 *  1. Streamable HTTP：
 *     - POST 单个请求体到同一端点(MCP 标准位置)
 *     - 响应可能是 application/json（一次性）或 text/event-stream（SSE 流）
 *     本实现从响应流里逐行读取，遇到匹配 id 的 JSON-RPC result 即返回。
 *  2. 旧版 SSE：
 *     - 先 GET 端点建立长连接，服务端下发 `event: endpoint` + `data: <url>`
 *     - 然后所有 JSON-RPC 请求 POST 到该 url
 *
 * 说明：MCP 的初始化顺序为 initialize -> notifications/initialized -> tools/list。
 * 本客户端会缓存 session id（通过 Mcp-Session-Id 响应头），保证同一次会话内一致。
 */
class McpClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val idGen = AtomicInteger(1)

    /**
     * 每个服务器的会话 id 缓存：serverId -> Mcp-Session-Id。
     *
     * 用 ConcurrentHashMap 而非普通 HashMap：同一个 McpClient 实例可能被
     * 多个协程（工具栏刷新、聚合服务器转发、AI 调用）并发使用，
     * 普通 HashMap 在并发 put/get 下会出现链表成环导致 get 死循环。
     */
    private val sessions = ConcurrentHashMap<String, String>()

    // ---------- 对外：拉取工具列表 ----------

    /**
     * 初始化会话并拉取该服务器全部工具。
     * 任何一个环节失败都抛出异常，由调用方统一提示。
     */
    suspend fun listTools(server: McpServerConfig): List<McpTool> = withContext(Dispatchers.IO) {
        // 1. initialize
        val initResult = rpc(server, "initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "MiniiChat-Next")
                put("version", "1.0")
            })
        })
        DebugLog.i(
            "Mcp",
            "initialize ok server=${server.name} result=${initResult.toString().take(200)}"
        )

        // 2. notifications/initialized（通知，无 id，不等回包）
        runCatching { notify(server, "notifications/initialized", buildJsonObject { }) }

        // 3. tools/list
        val listResult = rpc(server, "tools/list", buildJsonObject { })
        val toolsArr = listResult["tools"]?.jsonArray ?: JsonArray(emptyList())

        // 关键：必须把 serverId 写进每个 McpTool。
        // 上层（McpStore / UI 过滤 / AI 工具清单）全部依赖 tool.serverId == server.id 来归属工具，
        // 旧实现这里返回的 McpTool 没有 serverId（默认为空串），
        // 导致工具虽然成功拉取并写进了存储，但按 serverId 过滤时永远查不到 ——
        // 表现就是「提示已同步 N 个工具，但详情页和 AI 都看不到任何工具」。
        val parsed = toolsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val schema = obj["inputSchema"]?.let {
                json.encodeToString(JsonElement.serializer(), it)
            } ?: ""
            McpTool(
                name = name,
                description = desc,
                inputSchema = schema,
                serverId = server.id
            )
        }
        DebugLog.i("Mcp", "tools/list ok server=${server.name} count=${parsed.size}")
        parsed
    }

    // ---------- 对外：调用工具 ----------

    suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        argumentsJson: String
    ): McpToolResult = withContext(Dispatchers.IO) {
        val args = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        runCatching {
            val result = rpc(server, "tools/call", buildJsonObject {
                put("name", toolName)
                put("arguments", args)
            })
            val isError = result["isError"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            val text = extractContent(result)
            McpToolResult(ok = !isError, content = text)
        }.getOrElse { e ->
            DebugLog.e("Mcp", "callTool failed ${server.name}/$toolName: ${e.message}", e)
            McpToolResult(ok = false, content = "", error = e.message ?: "unknown error")
        }
    }

    /** 把 MCP tools/call 返回的 content 数组拼接成纯文本 */
    private fun extractContent(result: JsonObject): String {
        val content = result["content"] as? JsonArray ?: return result.toString()
        val sb = StringBuilder()
        for (c in content) {
            val obj = c as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                "resource" -> {
                    val res = obj["resource"] as? JsonObject
                    res?.get("text")?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                }
                else -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
            }
        }
        return sb.toString().ifBlank { result.toString() }
    }

    // ---------- JSON-RPC 核心 ----------

    private suspend fun rpc(
        server: McpServerConfig,
        method: String,
        params: JsonObject
    ): JsonObject {
        val id = idGen.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        return when (server.tp()) {
            McpTransport.STREAMABLE_HTTP -> streamableRpc(server, body, id)
            McpTransport.SSE -> sseRpc(server, body, id)
        }
    }

    /** 通知类方法：无 id，服务端即便返回也不解析 */
    private suspend fun notify(server: McpServerConfig, method: String, params: JsonObject) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString()
        val endpoint = if (server.tp() == McpTransport.SSE) sseEndpoint(server) else server.url
        runCatching {
            val conn = openPost(server, endpoint, body)
            // 通知服务端可能返回 202/204，无正文
            runCatching { conn.inputStream.use { it.readBytes() } }
            conn.disconnect()
        }
    }

    // ---------- Streamable HTTP ----------

    private fun streamableRpc(server: McpServerConfig, body: String, id: Int): JsonObject {
        val conn = openPost(server, server.url, body, acceptStream = true)
        // 记录会话 id
        conn.getHeaderField("Mcp-Session-Id")?.let { sessions[server.id] = it }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching {
                conn.errorStream?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            throw RuntimeException("HTTP $code: ${err.take(300)}")
        }
        val contentType = conn.contentType ?: ""
        val result = if (contentType.contains("text/event-stream", ignoreCase = true)) {
            readSseForResult(conn, id)
        } else {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parseRpcResult(text, id)
        }
        conn.disconnect()
        return result
    }

    // ---------- 旧版 SSE ----------

    /** 建立一次 GET SSE 连接拿到 endpoint url（用完即断） */
    private fun sseEndpoint(server: McpServerConfig): String {
        val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            connectTimeout = 20_000
            readTimeout = 20_000
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("SSE connect HTTP $code")
        }
        var endpoint = ""
        runCatching {
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var ev = ""
                var line: String?
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("event:")) {
                        ev = line.removePrefix("event:").trim()
                    } else if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (ev == "endpoint" || data.contains("/message") ||
                            data.startsWith("/")
                        ) {
                            endpoint = data
                            break
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (endpoint.isBlank()) throw RuntimeException("SSE endpoint not advertised")
        // endpoint 可能是相对路径，拼到 baseUrl 上
        return if (endpoint.startsWith("http")) {
            endpoint
        } else {
            "${baseRoot(server.url)}${if (endpoint.startsWith("/")) endpoint else "/$endpoint"}"
        }
    }

    private fun sseRpc(server: McpServerConfig, body: String, id: Int): JsonObject {
        val endpoint = sseEndpoint(server)
        val conn = openPost(server, endpoint, body, acceptStream = true)
        val code = conn.responseCode
        if (code !in 200..299 && code != 202) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.readText().orEmpty() }
                .getOrDefault("")
            conn.disconnect()
            throw RuntimeException("HTTP $code: ${err.take(300)}")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        // 旧版 SSE 的 POST 响应可能直接是 JSON，也可能是空（结果走 SSE 通道）
        // 这里优先解析正文；解析不到再尝试单独开一条 SSE 读 result
        runCatching { return parseRpcResult(text, id) }.getOrNull()
        return readSseSingle(server, id)
    }

    /** 单独开一条 SSE 连接读一次 result（兜底） */
    private fun readSseSingle(server: McpServerConfig, id: Int): JsonObject {
        val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 20_000
            readTimeout = 30_000
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        val result = readSseForResult(conn, id)
        conn.disconnect()
        return result
    }

    // ---------- 通用工具 ----------

    private fun openPost(
        server: McpServerConfig,
        endpoint: String,
        body: String,
        acceptStream: Boolean = false
    ): HttpURLConnection {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty(
                "Accept",
                if (acceptStream) "application/json, text/event-stream" else "application/json"
            )
            // 会话回传
            sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /** 从 SSE 流里读到匹配 id 的 JSON-RPC result 即返回 */
    private fun readSseForResult(conn: HttpURLConnection, id: Int): JsonObject {
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line: String?
            // 90s 内没拿到结果就超时
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    // 空行 = 一个 SSE 事件结束
                    if (sb.isNotEmpty()) {
                        val parsed = runCatching { parseRpcResult(sb.toString(), id) }.getOrNull()
                        if (parsed != null) return parsed
                        sb.setLength(0)
                    }
                    continue
                }
                if (line.startsWith("data:")) {
                    sb.append(line.removePrefix("data:").trim())
                }
            }
            // 流结束前再尝试一次
            if (sb.isNotEmpty()) {
                return parseRpcResult(sb.toString(), id)
            }
        }
        throw RuntimeException("SSE closed before result id=$id")
    }

    /** 解析 JSON-RPC 响应，取出 result / 抛 error */
    private fun parseRpcResult(text: String, id: Int): JsonObject {
        val obj = json.parseToJsonElement(text.trim()).jsonObject
        obj["error"]?.let { err ->
            val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: err.toString()
            throw RuntimeException("MCP error: $msg")
        }
        return obj["result"]?.jsonObject
            ?: throw RuntimeException("MCP response missing result (id=$id)")
    }

    /** 取 baseUrl 的 scheme://host[:port] 部分，用于拼接 SSE 相对 endpoint */
    private fun baseRoot(url: String): String {
        val u = URL(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.protocol}://${u.host}$port"
    }

    /** 建立会话后清理（服务器侧无更强要求时可忽略） */
    fun forget(serverId: String) {
        sessions.remove(serverId)
    }
}
