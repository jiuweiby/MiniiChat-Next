package com.miniichatNext.carter.data.provider

import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.LlmClient
import com.miniichatNext.carter.api.ToolSpec
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.Role
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 提供商 / 模型连通性测试器。
 *
 * 三项独立测试，各自返回结构化的耗时、输出与错误，互不影响：
 *
 *  1. 流式（stream = true）
 *     验证 SSE 增量解析是否正常，统计首字节延迟与接收到的 chunk 数量。
 *     这一步最容易暴露的问题：服务端不返回 SSE、返回了非 data: 格式、中途切断。
 *
 *  2. 非流式（stream = false）
 *     验证一次性 JSON 响应解析。有些中转只支持其中一种模式，
 *     两项分开测才能定位到底是「接口不通」还是「流式不兼容」。
 *
 *  3. 工具调用（tools 参数 + 强制 tool_choice）
 *     验证模型是否理解并正确返回 function call 结构。
 *     旧实现从不校验这一步，导致用户配好提供商后在真实对话里
 *     才发现工具调用完全不生效，却以为是工具本身的问题。
 */
object ProviderTester {

    private const val TAG = "ProviderTest"

    /** 单次测试的整体超时 */
    private const val TEST_TIMEOUT_MS = 60_000L

    /** 测试用工具名，与内置工具不冲突 */
    private const val PROBE_TOOL_NAME = "get_current_weather"

    enum class Kind(val label: String) {
        STREAM("流式响应"),
        NON_STREAM("非流式响应"),
        TOOL_CALL("工具调用")
    }

    /**
     * 单项测试结果。
     *
     * @param kind        测试类型
     * @param ok          是否通过
     * @param latencyMs   从发起请求到拿到首个有效内容的耗时
     * @param totalMs     整项测试总耗时
     * @param chunks      流式模式下收到的 chunk 数量；其它模式为 1
     * @param detail      成功时的摘要信息（输出片段、工具名、参数等）
     * @param error       失败时的错误信息，直接来自异常 message，不做包装
     * @param rawResponse 失败时尽量保留的原始响应片段，便于排查
     */
    data class Result(
        val kind: Kind,
        val ok: Boolean,
        val latencyMs: Long = 0,
        val totalMs: Long = 0,
        val chunks: Int = 0,
        val detail: String = "",
        val error: String? = null,
        val rawResponse: String = ""
    )

    /**
     * 执行全部三项测试。
     *
     * 顺序执行而不是并发：并发会把不同测试的耗时混在一起，
     * 且部分中转对同 IP 的并发请求有限流，容易造成误判。
     *
     * @param onProgress 每完成一项就回调一次，便于 UI 逐项点亮
     */
    suspend fun runAll(
        provider: ProviderConfig,
        modelId: String,
        settings: AppSettings,
        onProgress: (Result) -> Unit
    ) {
        val results = listOf(
            testStream(provider, modelId, settings),
            testNonStream(provider, modelId, settings),
            testToolCall(provider, modelId, settings)
        )
        results.forEach { onProgress(it) }
    }

    // ---------------------------------------------------------------
    // 1. 流式
    // ---------------------------------------------------------------

    private suspend fun testStream(
        provider: ProviderConfig,
        modelId: String,
        settings: AppSettings
    ): Result = runTest(Kind.STREAM) {
        val client = LlmClient()
        val probe = settings.copy(stream = true, temperature = 0f, aiCacheMode = "关闭")
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "只回复两个字：收到")
        )

        var chunks = 0
        val sb = StringBuilder()
        var firstChunkAt = 0L
        val start = System.currentTimeMillis()

        client.chatStream(
            provider = provider,
            settings = probe,
            modelId = modelId,
            messages = messages
        ).collect { delta ->
            val added = delta.text.orEmpty()
            if (added.isNotEmpty()) {
                if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis() - start
                chunks++
                sb.append(added)
            }
        }
        val total = System.currentTimeMillis() - start

        if (chunks == 0) {
            return@runTest Result(
                kind = Kind.STREAM,
                ok = false,
                totalMs = total,
                error = "连接成功但未收到任何内容增量。可能该渠道不支持 SSE 流式，" +
                    "或返回格式不是标准 data: 帧。"
            )
        }

        Result(
            kind = Kind.STREAM,
            ok = true,
            latencyMs = firstChunkAt,
            totalMs = total,
            chunks = chunks,
            detail = buildString {
                append("首块延迟 ").append(firstChunkAt).append("ms\n")
                append("共收到 ").append(chunks).append(" 个增量块\n")
                append("模型输出：").append(sb.toString().trim().take(300))
            }
        )
    }

    // ---------------------------------------------------------------
    // 2. 非流式
    // ---------------------------------------------------------------

    private suspend fun testNonStream(
        provider: ProviderConfig,
        modelId: String,
        settings: AppSettings
    ): Result = runTest(Kind.NON_STREAM) {
        val client = LlmClient()
        val probe = settings.copy(stream = false, temperature = 0f, aiCacheMode = "关闭")
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "只回复两个字：收到")
        )

        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        client.chatStream(
            provider = provider,
            settings = probe,
            modelId = modelId,
            messages = messages
        ).collect { delta ->
            sb.append(delta.text.orEmpty())
        }
        val total = System.currentTimeMillis() - start

        val text = sb.toString().trim()
        if (text.isEmpty()) {
            return@runTest Result(
                kind = Kind.NON_STREAM,
                ok = false,
                totalMs = total,
                error = "请求成功但响应正文为空。请检查该模型是否要求 stream=true，" +
                    "或是否被渠道内容策略拦截。"
            )
        }

        Result(
            kind = Kind.NON_STREAM,
            ok = true,
            latencyMs = total,
            totalMs = total,
            chunks = 1,
            detail = buildString {
                append("往返耗时 ").append(total).append("ms\n")
                append("模型输出：").append(text.take(300))
            }
        )
    }

    // ---------------------------------------------------------------
    // 3. 工具调用
    // ---------------------------------------------------------------

    private suspend fun testToolCall(
        provider: ProviderConfig,
        modelId: String,
        settings: AppSettings
    ): Result = runTest(Kind.TOOL_CALL) {
        val client = LlmClient()

        // 工具调用测试强制走流式，因为绝大多数客户端默认开启流式；
        // 若渠道仅在非流式下支持工具，这里会明确报出来，用户可据此调整。
        val probe = settings.copy(stream = true, temperature = 0f, aiCacheMode = "关闭")

        val tool = ToolSpec(
            name = PROBE_TOOL_NAME,
            description = "查询指定城市的当前天气。当用户询问天气时必须调用本工具。",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "city": {
                      "type": "string",
                      "description": "城市名称，例如 北京"
                    }
                  },
                  "required": ["city"]
                }
            """.trimIndent()
        )

        val messages = listOf(
            ChatMessage(
                role = Role.USER,
                content = "帮我查一下北京现在的天气。必须调用工具，不要直接回答。"
            )
        )

        val calls = mutableListOf<String>()
        val textParts = StringBuilder()
        val start = System.currentTimeMillis()

        client.chatStream(
            provider = provider,
            settings = probe,
            modelId = modelId,
            messages = messages,
            tools = listOf(tool)
        ).collect { delta ->
            delta.text?.let { textParts.append(it) }
            delta.toolCalls?.forEach { tc ->
                calls.add("工具=${tc.name}  参数=${tc.arguments}")
            }
        }
        val total = System.currentTimeMillis() - start

        if (calls.isEmpty()) {
            val modelSaid = textParts.toString().trim().take(300)
            return@runTest Result(
                kind = Kind.TOOL_CALL,
                ok = false,
                totalMs = total,
                error = buildString {
                    append("模型未返回任何工具调用。\n")
                    append("常见原因：\n")
                    append("· 该渠道未透传 tools 参数（部分中转会静默丢弃）\n")
                    append("· 该模型不支持 function calling\n")
                    append("· 模型选择直接回答而不是调用工具\n")
                    if (modelSaid.isNotEmpty()) {
                        append("模型的实际回复：").append(modelSaid)
                    }
                },
                rawResponse = modelSaid
            )
        }

        val first = calls.first()
        val nameOk = first.contains(PROBE_TOOL_NAME)

        Result(
            kind = Kind.TOOL_CALL,
            ok = nameOk,
            latencyMs = total,
            totalMs = total,
            chunks = calls.size,
            detail = buildString {
                append("往返耗时 ").append(total).append("ms\n")
                append("收到 ").append(calls.size).append(" 个工具调用\n")
                calls.forEachIndexed { i, c ->
                    append("  #").append(i + 1).append(' ').append(c).append('\n')
                }
            }.trimEnd(),
            error = if (nameOk) {
                null
            } else {
                "模型返回了工具调用，但工具名与请求的不一致（期望 $PROBE_TOOL_NAME，" +
                    "实际 $first）。这通常说明渠道对 tools 参数做了改写。"
            }
        )
    }

    // ---------------------------------------------------------------
    // 统一异常包装
    // ---------------------------------------------------------------

    /**
     * 统一执行包装。
     *
     * 异常处理要点：
     *   - 区分超时与业务异常，超时给出明确的排查方向
     *   - 错误信息保留原始 message，不做「测试失败」这种无信息量的包装
     *   - 尽量从异常里提取 HTTP 状态码与响应体，这对排查 401/404/429 至关重要
     */
    private suspend fun runTest(
        kind: Kind,
        block: suspend () -> Result
    ): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val guarded = withTimeoutOrNull(TEST_TIMEOUT_MS) {
            runCatching { block() }
        }

        when {
            guarded == null -> Result(
                kind = kind,
                ok = false,
                totalMs = System.currentTimeMillis() - start,
                error = "测试超时（超过 ${TEST_TIMEOUT_MS / 1000} 秒无响应）。\n" +
                    "请检查：网络是否通畅、Base URL 是否可达、" +
                    "该模型是否因排队或限流导致长时间无响应。"
            )

            guarded.isSuccess -> guarded.getOrThrow()

            else -> {
                val e = guarded.exceptionOrNull()!!
                DebugLog.e(TAG, "${kind.label} 测试异常: ${e.message}", e)
                Result(
                    kind = kind,
                    ok = false,
                    totalMs = System.currentTimeMillis() - start,
                    error = describeError(e),
                    rawResponse = extractRawBody(e)
                )
            }
        }
    }

    /** 把异常翻译成用户能直接照着排查的说明 */
    private fun describeError(e: Throwable): String {
        val raw = e.message.orEmpty()

        // 先处理已知的几种典型失败，命中就返回带排查建议的说明
        when {
            raw.contains("401") || raw.contains("Unauthorized") ->
                return "鉴权失败（HTTP 401）：API Key 无效、已过期，或该 Key 不属于当前 Base URL。"

            raw.contains("403") || raw.contains("Forbidden") ->
                return "访问被拒绝（HTTP 403）：Key 可能没有该模型的调用权限，或渠道限制了来源。"

            raw.contains("404") || raw.contains("Not Found") ->
                return "接口不存在（HTTP 404）：Base URL 或请求路径拼错。\n" +
                    "请确认 Base URL 是否已包含版本路径（如 /v1），" +
                    "以及 chat completions 路径是否与该渠道一致。"

            raw.contains("413") ->
                return "请求体过大（HTTP 413）：通常与 max tokens 或附带内容长度有关。"

            raw.contains("429") ->
                return "触发限流（HTTP 429）：请求过于频繁或额度耗尽，稍后重试或检查账户余额。"

            raw.contains("500") || raw.contains("502") ||
                raw.contains("503") || raw.contains("504") ->
                return "服务端错误（HTTP $raw）：渠道侧故障，与本地配置无关，可稍后重试。"

            raw.contains("Unable to resolve host") || raw.contains("UnknownHost") ->
                return "域名解析失败：Base URL 的域名拼写有误，或当前网络无法解析。"

            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ->
                return "连接超时：目标地址不可达，或需要代理但未配置。"

            raw.contains("SSL", ignoreCase = true) ||
                raw.contains("certificate", ignoreCase = true) ->
                return "TLS 握手失败：证书校验不通过，可能被中间设备拦截或渠道证书配置异常。"

            raw.contains("failed to connect", ignoreCase = true) ||
                raw.contains("Connection refused", ignoreCase = true) ->
                return "无法建立连接：目标端口未开放，或该渠道屏蔽了当前网络。"

            raw.isBlank() ->
                return "测试失败，但异常未提供任何错误信息（${e.javaClass.simpleName}）"
        }

        return raw
    }

    /** 从异常信息里尽量提取原始响应体，供界面展示细节 */
    private fun extractRawBody(e: Throwable): String {
        val raw = e.message.orEmpty()
        val idx = raw.indexOf("{")
        return if (idx >= 0) raw.substring(idx).take(2000) else ""
    }
}
