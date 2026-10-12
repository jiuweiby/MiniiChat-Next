package com.miniichatNext.carter.data.mcp

import android.content.Context
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 工具聚合服务器。
 *
 * 作用：把本机 App 当前持有的全部工具（内置文件工具 + 所有已启用的外部 MCP 服务器工具）
 * 聚合为单一标准 MCP 端点，供其他 APP / 桌面客户端接入调用。
 *
 * 协议实现要点（对齐 MCP 2024-11-05 Streamable HTTP）：
 *  - 端点 POST /mcp，请求与响应均为 application/json
 *  - initialize -> notifications/initialized -> tools/list -> tools/call
 *  - 通知类请求（无 id）返回 202 且无正文
 *  - 会话通过 Mcp-Session-Id 响应头下发，客户端后续请求需回传
 *  - GET /mcp 返回 405，表明本实现不支持服务端主动推送（规范允许）
 *
 * 设计约束（重要）：
 *  1. 所有需要读 McpStore 的分支只允许在 worker 线程执行（本类的请求处理都在线程池里），
 *     绝不能被 UI 线程调用 —— 因此对外只暴露 [toolCountCached] 这类纯内存接口给 UI。
 *  2. 工具名解析不做任何共享可变状态表，每次按当前配置即时推导，
 *     避免并发请求之间互相清空映射表导致「未知工具」。
 */
object McpAggregateServer {

    private const val TAG = "McpAggregate"
    const val IMPLEMENTATION_NAME = "MiniiChat-Next Aggregate"
    const val IMPLEMENTATION_VERSION = "1.0"
    private const val PROTOCOL_VERSION = "2024-11-05"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val sessionSeq = AtomicInteger(1)

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "mcp-aggregate-worker").apply { isDaemon = true }
    }

    @Volatile
    private var ctx: Context? = null

    /** 当前生效的配置快照，供请求处理时读取 */
    @Volatile
    private var activeConfig: McpAggregateConfig = McpAggregateConfig()

    /**
     * 供 UI 读取的工具计数缓存。
     * UI 不能触碰 McpStore（会阻塞主线程），因此这里由后台在配置变更时刷新一次。
     */
    @Volatile
    private var cachedToolCount: Int = 0

    /** 供 UI 读取的工具名缓存，同样由后台刷新 */
    @Volatile
    private var cachedToolNames: List<String> = emptyList()

    // ---------- 生命周期 ----------

    fun init(appContext: Context) {
        ctx = appContext.applicationContext
    }

    /**
     * 按配置启动 / 重启 / 停止服务器。
     * 幂等：重复调用同一配置不会重复绑定端口。
     */
    @Synchronized
    fun apply(appContext: Context, config: McpAggregateConfig) {
        ctx = appContext.applicationContext
        activeConfig = config

        if (!config.enabled) {
            stopInternal()
            cachedToolCount = 0
            cachedToolNames = emptyList()
            return
        }
        // 已运行且端口未变则只刷新配置即可
        if (running.get() && serverSocket?.localPort == config.port) {
            DebugLog.i(TAG, "配置已刷新，端口保持 ${config.port}")
            refreshCacheAsync()
            return
        }
        stopInternal()
        startInternal(config)
        refreshCacheAsync()
    }

    @Synchronized
    fun stop() {
        stopInternal()
        cachedToolCount = 0
        cachedToolNames = emptyList()
    }

    fun isRunning(): Boolean = running.get()

    fun currentPort(): Int = serverSocket?.localPort ?: activeConfig.port

    /** UI 安全：纯内存读取，不触碰 DataStore */
    fun toolCountCached(): Int = cachedToolCount

    /** UI 安全：纯内存读取，不触碰 DataStore */
    fun toolNamesCached(): List<String> = cachedToolNames

    /** 在后台线程刷新缓存，供 UI 展示使用 */
    fun refreshCacheAsync() {
        val context = ctx ?: return
        val config = activeConfig
        pool.execute {
            runCatching {
                // Route 是普通类而非 Pair，必须取具名属性 publicName
                val names = collectTools(context, config).map { it.publicName }
                cachedToolNames = names
                cachedToolCount = names.size
            }.onFailure {
                DebugLog.w(TAG, "刷新工具缓存失败: ${it.message}")
            }
        }
    }

    // ---------- 启停内部实现 ----------

    private fun startInternal(config: McpAggregateConfig) {
        val port = config.port.coerceIn(McpAggregateStore.MIN_PORT, McpAggregateStore.MAX_PORT)
        try {
            val address = if (config.loopbackOnly) {
                InetAddress.getByName("127.0.0.1")
            } else {
                null
            }
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 32)
            serverSocket = socket
            running.set(true)

            acceptThread = Thread({ acceptLoop(socket) }, "mcp-aggregate-accept").apply {
                isDaemon = true
                start()
            }
            val host = if (config.loopbackOnly) "127.0.0.1" else "0.0.0.0"
            DebugLog.i(
                TAG,
                "聚合 MCP 服务已启动: http://$host:$port${config.endpointPath}"
            )
        } catch (e: Throwable) {
            running.set(false)
            serverSocket = null
            DebugLog.e(TAG, "聚合 MCP 服务启动失败(端口 $port): ${e.message}", e)
        }
    }

    private fun stopInternal() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        DebugLog.i(TAG, "聚合 MCP 服务已停止")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                // close() 触发的正常退出路径
                break
            } catch (e: Throwable) {
                if (running.get()) DebugLog.w(TAG, "accept 异常: ${e.message}")
                break
            }
            runCatching { pool.execute { handleClient(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    // ---------- HTTP 层 ----------

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream())

            val request = readRequest(input) ?: run {
                writeSimple(output, 400, "Bad Request", "text/plain", "malformed request")
                return
            }

            val method = request.method.uppercase()
            val path = request.path.substringBefore('?')

            if (path != activeConfig.endpointPath) {
                writeSimple(
                    output, 404, "Not Found", "text/plain",
                    "endpoint is ${activeConfig.endpointPath}"
                )
                return
            }

            if (method == "OPTIONS") {
                writeEmpty(output, 204, extraHeaders = corsHeaders())
                return
            }

            if (method == "GET") {
                // Streamable HTTP 允许服务端用 GET 开 SSE 推送通道；
                // 本实现不做主动推送，按规范返回 405 即可，客户端会自动降级为纯 POST。
                writeSimple(
                    output, 405, "Method Not Allowed", "text/plain",
                    "SSE push channel not supported"
                )
                return
            }

            if (method != "POST") {
                writeSimple(
                    output, 405, "Method Not Allowed", "text/plain",
                    "only POST supported"
                )
                return
            }

            // 令牌校验
            if (activeConfig.requireToken) {
                val token = activeConfig.token
                val provided = request.headers["authorization"]
                    ?.removePrefix("Bearer ")?.trim()
                    ?: request.headers["x-mcp-token"]?.trim()
                if (token.isNotBlank() && provided != token) {
                    writeSimple(
                        output, 401, "Unauthorized", "text/plain",
                        "invalid or missing token"
                    )
                    return
                }
            }

            val body = request.body
            if (body.isBlank()) {
                writeSimple(output, 400, "Bad Request", "text/plain", "empty body")
                return
            }

            val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (element == null) {
                writeSimple(output, 400, "Bad Request", "text/plain", "invalid json")
                return
            }

            val sessionId = request.headers["mcp-session-id"]
                ?: "sess-${sessionSeq.getAndIncrement()}-${System.currentTimeMillis()}"

            // 每次都按当前配置即时推导工具清单与路由。
            // 不做共享映射表，因此并发请求之间互不影响。
            val context = ctx
            val routes = if (context == null) {
                emptyList()
            } else {
                runCatching { collectTools(context, activeConfig) }.getOrDefault(emptyList())
            }

            val responses: List<JsonObject> = when (element) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }
                    .mapNotNull { handleRpc(it, context, routes) }
                is JsonObject -> listOfNotNull(handleRpc(element, context, routes))
                else -> emptyList()
            }

            val extra = buildMap {
                putAll(corsHeaders())
                put("Mcp-Session-Id", sessionId)
            }

            if (responses.isEmpty()) {
                // 全部是通知：按规范回 202 Accepted，无正文
                writeEmpty(output, 202, extraHeaders = extra)
            } else {
                val payload = if (responses.size == 1) {
                    responses.first().toString()
                } else {
                    JsonArray(responses).toString()
                }
                writeSimple(output, 200, "OK", "application/json", payload, extra)
            }
        } catch (e: Throwable) {
            DebugLog.w(TAG, "处理客户端请求失败: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
        "Access-Control-Allow-Headers" to
            "Content-Type, Authorization, Mcp-Session-Id, X-Mcp-Token",
        "Access-Control-Expose-Headers" to "Mcp-Session-Id"
    )

    // ---------- 极简 HTTP 解析 ----------

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    /** 一次可外暴工具的描述：对外名 -> 真实来源 */
    private class Route(
        val publicName: String,
        val description: String,
        val inputSchema: String,
        /** null 表示内置工具 */
        val serverId: String?,
        val realName: String
    )

    private fun readRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        // 逐字节读到 \r\n\r\n，避免一次性 read 把 body 也吞掉
        while (true) {
            val b = input.read()
            if (b < 0) break
            headerBytes.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code &&
                prev1 == '\r'.code && b == '\n'.code
            ) break
            prev3 = prev2
            prev2 = prev1
            prev1 = b
            if (headerBytes.size() > 64 * 1024) return null
        }
        if (headerBytes.size() == 0) return null

        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val method = requestLine[0]
        val path = requestLine[1]

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx <= 0) continue
            headers[lines[i].substring(0, idx).trim().lowercase()] =
                lines[i].substring(idx + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            if (headers["transfer-encoding"]?.contains("chunked") == true) {
                readChunked(input)
            } else {
                ""
            }
        }

        return HttpRequest(method, path, headers, body)
    }

    private fun readChunked(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().split(';').first().trim().toIntOrNull(16) ?: break
            if (size <= 0) {
                readLine(input)
                break
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n < 0) break
                read += n
            }
            out.write(buf, 0, read)
            readLine(input)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toInt().toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun writeSimple(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) ->
            head.append(k).append(": ").append(v).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun writeEmpty(
        output: BufferedOutputStream,
        code: Int,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append("\r\n")
        head.append("Content-Length: 0\r\n")
        head.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) ->
            head.append(k).append(": ").append(v).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    // ---------- JSON-RPC 层 ----------

    /**
     * 处理单条 JSON-RPC 请求。
     * 返回 null 表示这是通知（无 id），不需要回包。
     */
    private fun handleRpc(
        req: JsonObject,
        context: Context?,
        routes: List<Route>
    ): JsonObject? {
        val idElement = req["id"]
        val method = (req["method"] as? JsonPrimitive)?.contentOrNullSafe()
        val params = req["params"] as? JsonObject

        if (method == null) {
            return idElement?.let { errResponse(it, -32600, "Invalid Request: missing method") }
        }

        // 无 id 即通知
        if (idElement == null || idElement is JsonNull) {
            DebugLog.d(TAG, "收到通知: $method")
            return null
        }

        return try {
            when (method) {
                "initialize" -> okResponse(idElement, buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", false) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", IMPLEMENTATION_NAME)
                        put("version", IMPLEMENTATION_VERSION)
                    })
                })

                "ping" -> okResponse(idElement, buildJsonObject { })

                "tools/list" -> okResponse(idElement, buildJsonObject {
                    put("tools", buildJsonArray {
                        routes.forEach { route ->
                            add(buildJsonObject {
                                put("name", route.publicName)
                                put("description", route.description)
                                val schema = runCatching {
                                    json.parseToJsonElement(
                                        route.inputSchema.ifBlank { """{"type":"object"}""" }
                                    )
                                }.getOrNull() ?: buildJsonObject { put("type", "object") }
                                put("inputSchema", schema)
                            })
                        }
                    })
                })

                "tools/call" -> {
                    val name = (params?.get("name") as? JsonPrimitive)?.contentOrNullSafe()
                    if (name.isNullOrBlank()) {
                        okResponse(idElement, toolError("缺少工具名 name 参数"))
                    } else {
                        val args = params["arguments"] ?: buildJsonObject { }
                        val argsText = runCatching { args.toString() }.getOrDefault("{}")
                        val result = dispatchTool(context, routes, name, argsText)
                        okResponse(idElement, buildJsonObject {
                            put("isError", !result.ok)
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put(
                                        "text",
                                        if (result.ok) result.content
                                        else (result.error ?: "执行失败")
                                    )
                                })
                            })
                        })
                    }
                }

                "prompts/list" -> okResponse(idElement, buildJsonObject {
                    put("prompts", buildJsonArray { })
                })

                "resources/list" -> okResponse(idElement, buildJsonObject {
                    put("resources", buildJsonArray { })
                })

                else -> errResponse(idElement, -32601, "Method not found: $method")
            }
        } catch (e: Throwable) {
            DebugLog.e(TAG, "处理 $method 失败: ${e.message}", e)
            errResponse(idElement, -32603, "Internal error: ${e.message}")
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    private fun okResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun errResponse(id: JsonElement, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }

    private fun toolError(message: String): JsonObject = buildJsonObject {
        put("isError", true)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", message)
            })
        })
    }

    // ---------- 工具聚合与路由 ----------

    /**
     * 即时推导当前可外暴的工具清单。
     *
     * 每次调用都重新读取配置与下游列表，不保留跨请求的共享状态，
     * 因此并发调用之间不会互相污染。
     *
     * 注意：内部会 runBlocking 读 DataStore，只允许在 worker 线程调用。
     */
    private fun collectTools(context: Context, config: McpAggregateConfig): List<Route> {
        val store = McpStore(context)
        val routes = ArrayList<Route>()

        // 1. 内置文件工具
        if (config.exposeBuiltIn) {
            BuiltInFileTools.schema.forEach { tool ->
                routes.add(
                    Route(
                        publicName = uniqueName(tool.name, routes),
                        description = tool.description,
                        inputSchema = tool.inputSchema,
                        serverId = null,
                        realName = tool.name
                    )
                )
            }
        }

        // 2. 外部 MCP 服务器工具（只取已启用的服务器）
        if (config.exposeExternal) {
            val servers = runCatching { runBlocking { store.snapshot() } }
                .getOrDefault(emptyList())
            val tools = runCatching { runBlocking { store.toolsSnapshot() } }
                .getOrDefault(emptyList())
            val enabledIds = servers.filter { it.enabled }.map { it.id }.toSet()

            tools.forEach { tool ->
                // serverId 为空是内置工具的占位，已在上面处理，跳过避免重复
                if (tool.serverId.isBlank()) return@forEach
                if (tool.serverId !in enabledIds) return@forEach

                routes.add(
                    Route(
                        publicName = uniqueName(tool.name, routes),
                        description = tool.description,
                        inputSchema = tool.inputSchema,
                        serverId = tool.serverId,
                        realName = tool.name
                    )
                )
            }
        }

        return routes
    }

    /** 同名工具自动加前缀去重，前缀取原名前 3 字符大写 */
    private fun uniqueName(base: String, existing: List<Route>): String {
        if (existing.none { it.publicName == base }) return base
        val prefix = base.take(3).uppercase()
        var candidate = "${prefix}_$base"
        var i = 1
        while (existing.any { it.publicName == candidate }) {
            candidate = "${prefix}${i}_$base"
            i++
        }
        return candidate
    }

    /** 把聚合工具名路由回真实执行入口 */
    private fun dispatchTool(
        context: Context?,
        routes: List<Route>,
        publicName: String,
        argsJson: String
    ): McpToolResult {
        if (context == null) return McpToolResult(false, "", "服务未初始化")

        val route = routes.firstOrNull { it.publicName == publicName }
            ?: return McpToolResult(false, "", "未知工具: $publicName")

        return try {
            if (route.serverId == null) {
                // 内置工具本地执行
                BuiltInFileTools.execute(route.realName, argsJson)
            } else {
                val store = McpStore(context)
                val server = runBlocking { store.snapshot() }.find { it.id == route.serverId }
                if (server == null) {
                    McpToolResult(false, "", "下游 MCP 服务器不可用: ${route.serverId}")
                } else {
                    runBlocking { McpClient().callTool(server, route.realName, argsJson) }
                }
            }
        } catch (e: Throwable) {
            DebugLog.e(TAG, "工具 $publicName 执行异常: ${e.message}", e)
            McpToolResult(false, "", "执行异常: ${e.message}")
        }
    }
}
