package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderType
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.debug.DebugLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class LlmClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
    }

    /**
     * 判断当前模型是否属于生图/文生图模型
     */
    private fun isImageGenerationModel(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return lower.contains("image") ||
                lower.contains("dall-e") ||
                lower.contains("midjourney") ||
                lower.contains("flux") ||
                lower.contains("stable-diffusion")
    }

    internal fun buildRequestBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList()
    ): String {
        val modelCfg = provider.model(modelId)
        return when {
            provider.type() == ProviderType.CLAUDE ->
                buildClaudeBody(provider, modelId, messages, stream, temperature, tools)
            modelCfg?.useResponseApi == true ->
                buildResponseBody(provider, modelId, messages, stream, temperature)
            else ->
                buildOpenAiBody(provider, modelId, messages, stream, temperature, tools)
        }
    }

    fun chatStream(
        provider: ProviderConfig,
        settings: AppSettings,
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList()
    ): Flow<StreamDelta> = flow {
        // 如果当前是生图模型，自动切换到 OpenAI 兼容的 /images/generations 接口
        if (isImageGenerationModel(modelId)) {
            val lastUserPrompt = messages.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
            generateImageStream(provider, modelId, lastUserPrompt).collect { emit(it) }
            return@flow
        }

        val bodyText = buildRequestBody(
            provider = provider,
            modelId = modelId,
            messages = messages,
            stream = settings.stream,
            temperature = settings.temperature,
            tools = tools
        )
        val isClaude = provider.type() == ProviderType.CLAUDE
        val endpoint = when {
            isClaude -> claudeMessagesEndpoint(provider.baseUrl)
            provider.model(modelId)?.useResponseApi == true -> responsesEndpoint(provider.baseUrl)
            else -> chatEndpoint(provider.baseUrl, provider.chatCompletionsPath)
        }
        val fallbackEndpoint = if (isClaude && claudeNeedsV1Fallback(provider.baseUrl)) {
            claudeV1MessagesEndpoint(provider.baseUrl)
        } else null
        DebugLog.d(
            "Llm",
            "chatStream provider=${provider.name} type=${provider.type()} model=$modelId " +
                "messages=${messages.size} stream=${settings.stream} tools=${tools.size} " +
                "endpoint=$endpoint bodyChars=${bodyText.length}"
        )
        // 打印本轮工具调用与 tool 回复的配对快照。
        // 这是定位 HTTP 400「tool calls and tool results do not match」的唯一直接证据：
        // 上游只校验报文本身，只要这里列出的 id 与回复数量能对齐，报文就是合法的。
        //
        // 关键：这里不只是「检查 + 打日志」，而是**硬拦截**。
        // 如果发现问题却仍把报文发出去，结果必然是浪费一次请求、拿到一个
        // 语义模糊的 400，并让用户以为「需要开新会话」——
        // 实际上这是客户端自己的历史数据坏了，必须在本地就拦住并说清楚。
        if (!isClaude) {
            val pairingIssues = debugToolPairingInBody(messages)
            if (pairingIssues.isNotEmpty()) {
                throw RuntimeException(
                    "本轮到 ${provider.name} 的请求已被本地拦截：工具调用与工具回复配对异常，" +
                        "直接发送必然被上游以 HTTP 400 拒绝。\n" +
                        pairingIssues.joinToString("\n") { "  · $it" } +
                        "\n请重试；若持续出现，请反馈该会话的历史消息（这是客户端状态回写缺陷，非网络问题）。"
                )
            }
        }
        suspend fun doPost(target: String) {
            DebugLog.i("Llm", "POST $target")
            client.preparePost(target) {
                contentType(ContentType.Application.Json)
                headers {
                    if (isClaude) {
                        for ((k, v) in claudeAuthHeaders(provider)) append(k, v)
                    } else {
                        if (provider.apiKey.isNotBlank()) {
                            append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                        }
                        for ((k, v) in provider.customHeaders) {
                            if (k.isBlank()) continue
                            append(k, v)
                        }
                    }
                    append(
                        HttpHeaders.Accept,
                        if (settings.stream) "text/event-stream" else "application/json"
                    )
                }
                setBody(bodyText)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                    DebugLog.e(
                        "Llm",
                        "HTTP ${response.status.value} from $target, body=${errBody.take(500)}"
                    )
                    val msg = "HTTP ${response.status.value}: ${errBody.take(500)}"
                    if (isClaude && response.status == HttpStatusCode.NotFound) {
                        throw ClaudeEndpoint404(msg)
                    }
                    throw RuntimeException(msg)
                }
                DebugLog.d("Llm", "HTTP ${response.status.value} OK from $target (stream=${settings.stream})")
                if (settings.stream) {
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    when (provider.type()) {
                        ProviderType.OPENAI -> readOpenAiStream(channel)
                        ProviderType.CLAUDE -> readClaudeStream(channel)
                    }
                } else {
                    when (provider.type()) {
                        ProviderType.OPENAI -> readOpenAiNonStream(response)
                        ProviderType.CLAUDE -> readClaudeNonStream(response)
                    }
                }
            }
        }
        if (fallbackEndpoint != null) {
            try {
                doPost(endpoint)
            } catch (e: ClaudeEndpoint404) {
                doPost(fallbackEndpoint)
            }
        } else {
            doPost(endpoint)
        }
    }

    /**
     * 打印待发送消息里工具调用与 tool 回复的配对情况。
     *
     * ## 为什么需要这个
     *
     * 上游返回 400 时只给一句
     * `tool calls and tool results do not match`，不会说明是哪一条消息出了问题。
     * 而真正参与校验的只有发给它的报文，所以必须在发送前把配对关系摊开来看。
     *
     * 输出形如：
     * ```
     * Llm 工具配对检查：声明 2 笔 [call_a, call_b]，回复 2 笔 [call_a, call_b]
     * ```
     * 一旦出现 `缺失=[call_b]` 或 `孤儿=[call_x]`，就能直接定位到是哪条历史消息坏了。
     *
     * ## 与 ChatViewModelMessage.validateToolPairing 的区别
     *
     * 那一处校验的是「本地会话转换后的中间结果」，
     * 这一处校验的是「真正要发出去的 ChatMessage 列表」。
     *
     * 两者都要看：如果中间结果正常、这里异常，说明问题在 buildOpenAiBody 的序列化；
     * 如果中间结果就异常，问题在会话历史本身。
     *
     * ## 返回值
     *
     * 返回「配对问题清单」：
     * - 空列表：报文合法，可以安全发出；
     * - 非空：表示这份 body 一定会被上游以 HTTP 400
     *   「tool calls and tool results do not match」拒绝，调用方必须**阻断发送**。
     *
     * 历史教训：这里原先只打日志不阻断，导致带病报文照样被 POST 出去，
     * 用户侧只能看到一个来自上游的 400 和「请开新会话重试」的误导性提示。
     */
    private fun debugToolPairingInBody(messages: List<ChatMessage>): List<String> {
        val declared = LinkedHashSet<String>()
        val answered = LinkedHashSet<String>()
        val orphan = mutableListOf<String>()
        val nameless = mutableListOf<String>()

        for (m in messages) {
            if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
                for (tc in m.toolCalls) {
                    if (tc.id.isBlank()) {
                        nameless.add("assistant.tool_calls 中存在 id 为空的调用（name=${tc.name}）")
                    } else {
                        declared.add(tc.id)
                    }
                }
            } else if (m.role == "tool") {
                val id = m.toolCallId
                if (id.isNullOrBlank()) {
                    orphan.add("存在 tool_call_id 为空的 tool 回复（content 前 40 字：${m.content.take(40)}）")
                } else {
                    if (id !in declared) {
                        orphan.add("孤儿 tool 回复 id='$id'")
                    }
                    answered.add(id)
                }
            }
        }

        val missing = declared - answered
        val problems = buildList {
            if (missing.isNotEmpty()) add("缺失 tool 回复：${missing.joinToString("、")}")
            if (orphan.isNotEmpty()) addAll(orphan)
            if (nameless.isNotEmpty()) addAll(nameless)
        }

        if (problems.isEmpty()) {
            DebugLog.i(
                "Llm",
                "工具配对检查通过：声明 ${declared.size} 笔 [${declared.joinToString("、")}]，" +
                    "回复 ${answered.size} 笔 [${answered.joinToString("、")}]"
            )
            return emptyList()
        } else {
            DebugLog.e(
                "Llm",
                "工具配对检查失败（声明 ${declared.size} 笔，回复 ${answered.size} 笔）：\n" +
                    problems.joinToString("\n") { "  · $it" }
            )
            return problems
        }
    }

    /**
     * 文生图调用处理 (/images/generations)
     * 支持返回 url 或 b64_json，直接转为 Markdown 图片展示
     */
    private fun generateImageStream(
        provider: ProviderConfig,
        modelId: String,
        prompt: String
    ): Flow<StreamDelta> = flow {
        val endpoint = imagesGenerationsEndpoint(provider.baseUrl)
        val reqObj = buildJsonObject {
            put("model", modelId)
            put("prompt", prompt.ifBlank { "A beautiful wallpaper" })
            put("n", 1)
            put("response_format", "url")
        }
        val bodyText = json.encodeToString(JsonObject.serializer(), reqObj)
        emit(StreamDelta(text = "🎨 正在为您绘制图像，请稍候…\n\n"))

        val response = client.preparePost(endpoint) {
            contentType(ContentType.Application.Json)
            headers {
                if (provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
                append(HttpHeaders.Accept, "application/json")
            }
            setBody(bodyText)
        }.execute()

        if (!response.status.isSuccess()) {
            val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
            throw RuntimeException("HTTP ${response.status.value}: ${errBody.take(400)}")
        }

        val text = response.bodyAsText()
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw RuntimeException("无法解析生图返回结果")

        val dataArr = parsed["data"]?.jsonArray
        val firstItem = dataArr?.firstOrNull()?.jsonObject
        val url = firstItem?.get("url")?.jsonPrimitive?.contentOrNull
        val b64 = firstItem?.get("b64_json")?.jsonPrimitive?.contentOrNull

        val finalImageMarkdown = when {
            !url.isNullOrBlank() -> "![生成的图片]($url)"
            !b64.isNullOrBlank() -> "![生成的图片](data:image/png;base64,$b64)"
            else -> "生图完成但未找到图像数据: $text"
        }
        emit(StreamDelta(text = finalImageMarkdown))
    }

    suspend fun listModels(provider: ProviderConfig): List<String> = when (provider.type()) {
        ProviderType.CLAUDE -> listClaudeModels(provider)
        else -> listOpenAiModels(provider)
    }

    /** 把工具描述编码为 OpenAI tools 字段 */
    private fun toolsJson(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        for (t in tools) {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    val schema = runCatching {
                        json.parseToJsonElement(t.inputSchema.ifBlank { """{"type":"object"}""" })
                    }.getOrNull()
                    put("parameters", schema ?: buildJsonObject { put("type", "object") })
                }
            })
        }
    }

    /**
     * 构造 OpenAI 兼容的请求体。
     *
     * ## 工具字段的写入规则（与 400 报错直接相关）
     *
     * OpenAI 协议要求：assistant.tool_calls 里每一个 id，
     * 都必须在紧随其后的消息里找到一条 role=tool 且 tool_call_id 相同的回复。
     *
     * 这里有两个容易被忽略的坑，都出自「字段可能为空」：
     *
     * ### 坑一：tool_call_id 为空时字段被整体省略
     *
     *     m.toolCallId?.let { put("tool_call_id", it) }
     *
     * 当 toolCallId 为 null 或空串时，`let` 不执行，
     * 这条 tool 消息发出去就**完全没有 tool_call_id 字段**。
     * 上游看到一条无处归属的 tool 回复，只能判定配对失败。
     *
     * 对比同一分支里的 content 字段：
     *
     *     put("content", m.content.ifBlank { "(empty)" })
     *
     * 内容为空时给了占位（说明写代码时已经预见到空值），
     * 但 tool_call_id 漏了同样的兜底 —— 这个不对称就是缺陷所在。
     *
     * ### 坑二：id 为空串时写入了空字符串
     *
     *     put("id", tc.id)
     *
     * assistant 侧把空 id 当成有效值写了进去（`"id": ""`），
     * 而 tool 侧遇到空值是**省略字段**。同样是空值，两侧处理方式不一致，
     * 配对自然对不上。
     *
     * 现在的处理：两侧都按同一个规则生成兜底 id。
     * 由于这里拿到的是已经构建好的 ChatMessage（没有再往下传索引），
     * 用消息序号 + 调用序号组合成 `call_fallback_<msgIdx>_<callIdx>`，
     * 保证同一次请求内唯一且两侧一致。
     *
     * 需注意：兜底 id 只在**两侧都为空的极少数情况**下才会用到。
     * 正常情况下 ChatViewModelMessage.buildApiMessages 已经补全了 id，
     * 这里只是防止有别的调用方直接构造 ChatMessage 时漏掉。
     */
    private fun buildOpenAiBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList()
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        val msgsJson = buildJsonArray {
            messages.forEachIndexed { msgIdx, m ->
                add(buildJsonObject {
                    put("role", m.role)
                    if (m.role == "tool") {
                        // 空 tool_call_id 会直接导致上游配对失败，
                        // 必须写入一个非空值，不能省略字段
                        val callId = m.toolCallId?.takeIf { it.isNotBlank() }
                            ?: "call_fallback_${msgIdx}_reply"
                        put("tool_call_id", callId)
                        put("content", m.content.ifBlank { "(empty)" })
                    } else if (m.toolCalls != null && m.toolCalls.isNotEmpty()) {
                        put("content", m.content)
                        put("tool_calls", buildJsonArray {
                            m.toolCalls.forEachIndexed { callIdx, tc ->
                                add(buildJsonObject {
                                    // 与 tool 侧使用同一套兜底规则，保证两侧 id 一致
                                    val id = tc.id.takeIf { it.isNotBlank() }
                                        ?: "call_fallback_${msgIdx}_$callIdx"
                                    put("id", id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    }
                                })
                            }
                        })
                    } else if (m.isMultipart()) {
                        put("content", buildJsonArray {
                            for (part in m.parts!!) {
                                add(json.encodeToJsonElement(ChatPart.serializer(), part))
                            }
                        })
                    } else {
                        // 确保 content 字段绝不为 null，若空白则兜底空格，杜绝 New API / Gemini 报 contents is required
                        put("content", m.content.ifEmpty { " " })
                    }
                })
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", effTemp)
            put("messages", msgsJson)
            if (tools.isNotEmpty()) {
                put("tools", toolsJson(tools))
                put("tool_choice", "auto")
            }
            modelCfg?.maxTokens?.let { put("max_tokens", it) }
            for ((k, v) in provider.extraBody) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildResponseBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        val input = buildJsonArray {
            for (m in messages) {
                if (m.role == "system") {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", m.content)
                    })
                } else {
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", effTemp)
            put("input", input)
            modelCfg?.maxTokens?.let { put("max_output_tokens", it) }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private suspend fun listOpenAiModels(provider: ProviderConfig): List<String> {
        val resp = client.get(modelsEndpoint(provider.baseUrl)) {
            headers {
                if (provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
        }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw RuntimeException("HTTP ${resp.status.value}: ${err.take(300)}")
        }
        val text = resp.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ModelsResponse.serializer(), text)
        }.getOrNull()
        if (parsed != null && parsed.data.isNotEmpty()) {
            return parsed.data.map { it.id }.distinct().sorted()
        }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.distinct().sorted()
    }

    /**
     * OpenAI 流式解析（含 tool_calls 增量合并）。
     *
     * ## 工具调用合并
     *
     * 服务端会把一个工具调用拆成若干帧，通常第一帧带 id 与 name，
     * 后续帧只带 arguments 分片。但也有渠道在后续帧里同时带上
     * `"name": ""`（空字符串而非缺失），这时候如果用
     * `tc.function?.name ?: cur.second` 取值，空串会被当成有效值写进累积状态，
     * 把已经拿到的工具名覆盖成空 —— 这正是「工具名为空」的成因。
     * 因此这里改用 isNullOrBlank() 判定，只有真正有内容时才更新名字。
     *
     * 另外为了兼容非标准实现，这里还处理了两种变体：
     *   - choice.message 里直接给出完整 tool_calls（部分网关在 stream 模式下发完整 message）
     *   - 同一 index 重复出现 id / name（只取第一次非空值，后续忽略）
     *
     * ## 为什么收到 finishReason 就要立刻结束读取
     *
     * finish_reason 表示服务端已经生成完毕，本轮不可能再有任何有效内容。
     *
     * 但部分网关在发出 finish_reason 之后既不发 [DONE]，也不关闭连接，
     * 甚至还会继续发送 keep-alive 空行。此时如果继续 readUTF8Line()，
     * 会一直阻塞到 socket 读超时（本类配置为 180 秒）。
     *
     * 表现就是：模型已经完整回复完（含工具调用），界面却卡在「正在输入」，
     * 直到三分钟后超时报错。这就是「AI 回复后一直加载不结束」的根因。
     *
     * 修复方式是收到任何非空 finish_reason 就 break，主动结束流读取，
     * 不再依赖服务端关闭连接。
     */
    private suspend fun FlowCollector<StreamDelta>.readOpenAiStream(
        channel: ByteReadChannel
    ) {
        /** index -> (id, name, argsBuilder) */
        val acc = LinkedHashMap<Int, Triple<String, String, StringBuilder>>()
        var rawFrames = 0
        var sawFinish = false

        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isEmpty()) continue

            // SSE 的注释行（以冒号开头）是心跳，直接跳过
            if (line.startsWith(":")) continue

            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            rawFrames++

            val chunk = runCatching {
                json.decodeFromString(ChatChunk.serializer(), payload)
            }.getOrNull() ?: continue

            val choice = chunk.choices.firstOrNull()

            // ---------- 变体一：标准 delta ----------
            val delta = choice?.delta
            val text = delta?.content.orEmpty()
            val reasoning = delta?.reasoningContent.orEmpty()
            delta?.toolCalls?.forEach { tc ->
                val idx = tc.index
                val cur = acc[idx]

                // id：只在当前有内容时更新，空串不覆盖已有值
                val newId = tc.id
                val id = if (!newId.isNullOrBlank()) newId else (cur?.first ?: "")

                // name：同样只在当前有内容时更新
                val newName = tc.function?.name
                val name = if (!newName.isNullOrBlank()) newName else (cur?.second ?: "")

                val argsBuilder = cur?.third ?: StringBuilder()
                tc.function?.arguments?.let { argsBuilder.append(it) }
                acc[idx] = Triple(id, name, argsBuilder)
            }

            // ---------- 变体二：非标准流式，直接给完整 message ----------
            val msg = choice?.message
            if (msg?.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                msg.toolCalls.forEachIndexed { i, tc ->
                    val idx = if (tc.id.isNotBlank()) {
                        // 优先按已有槽位归并，找不到则用新槽位
                        acc.keys.firstOrNull { acc[it]?.first == tc.id } ?: (acc.size + i)
                    } else {
                        i
                    }
                    val cur = acc[idx]
                    val id = tc.id.ifBlank { cur?.first ?: "" }
                    val name = tc.function?.name?.takeIf { it.isNotBlank() }
                        ?: cur?.second ?: ""
                    val argsBuilder = cur?.third ?: StringBuilder()
                    // 完整 message 里的 arguments 是最终值，直接覆盖而不是追加，
                    // 避免与先前分片累积的内容重复
                    val fullArgs = tc.function?.arguments
                    if (fullArgs != null) {
                        argsBuilder.clear()
                        argsBuilder.append(fullArgs)
                    }
                    acc[idx] = Triple(id, name, argsBuilder)
                }
                if (msg.content.isNotEmpty()) {
                    emit(StreamDelta(text = msg.content))
                }
            }

            if (text.isNotEmpty() || reasoning.isNotEmpty()) {
                emit(StreamDelta(text = text, reasoning = reasoning))
            }

            chunk.usage?.let {
                emit(StreamDelta(usage = TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens
                )))
            }

            // ---------- 收尾：finish_reason 指示本轮生成结束 ----------
            val reason = choice?.finishReason
            if (!reason.isNullOrBlank()) {
                sawFinish = true
                if (acc.isNotEmpty()) {
                    emitToolCalls(acc, rawFrames)
                    acc.clear()
                }
                // 已经收齐并发出结果，主动结束读取，不再等待 [DONE] 或连接关闭
                DebugLog.i(
                    "Llm",
                    "收到 finish_reason='$reason'，主动结束流读取（已处理 $rawFrames 帧）"
                )
                break
            }
        }

        // 流自然结束（连接关闭）但仍有残留分片时补发
        if (acc.isNotEmpty()) {
            emitToolCalls(acc, rawFrames)
        }

        if (!sawFinish) {
            DebugLog.i("Llm", "流自然结束（未收到 finish_reason，共 $rawFrames 帧）")
        }
    }

    /** 把累积的工具调用发出去，并在名字为空时记录诊断日志 */
    private suspend fun FlowCollector<StreamDelta>.emitToolCalls(
        acc: LinkedHashMap<Int, Triple<String, String, StringBuilder>>,
        rawFrames: Int
    ) {
        val calls = acc.entries.sortedBy { it.key }.map { (_, v) ->
            ToolCall(id = v.first, name = v.second, arguments = v.third.toString())
        }
        val nameless = calls.filter { it.name.isBlank() }
        if (nameless.isNotEmpty()) {
            DebugLog.e(
                "Llm",
                "收到的工具调用缺少 name（原始帧数=$rawFrames）：${namesDebug(acc)}"
            )
        }
        val usable = calls.filter { it.name.isNotBlank() }
        if (usable.isNotEmpty()) {
            DebugLog.i(
                "Llm",
                "工具调用就绪：" + usable.joinToString("、") {
                    "${it.name}(${it.id.ifBlank { "no-id" }})"
                }
            )
            emit(StreamDelta(toolCalls = usable))
        }
    }

    /** 生成工具调用累积状态的调试描述，用于定位服务端分片异常 */
    private fun namesDebug(
        acc: LinkedHashMap<Int, Triple<String, String, StringBuilder>>
    ): String = acc.entries.joinToString("；") { (idx, v) ->
        "idx=$idx id='${v.first}' name='${v.second}' argsLen=${v.third.length}"
    }

    private suspend fun FlowCollector<StreamDelta>.readOpenAiNonStream(
        response: io.ktor.client.statement.HttpResponse
    ) {
        val text = response.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ChatResponse.serializer(), text)
        }.getOrNull()
        val choice = parsed?.choices?.firstOrNull()
        val content = choice?.message?.content
        if (!content.isNullOrEmpty()) emit(StreamDelta(text = content))
        val calls = choice?.message?.toolCalls?.mapNotNull { tc ->
            val fn = tc.function ?: return@mapNotNull null
            if (fn.name.isBlank()) {
                DebugLog.e("Llm", "非流式响应里的工具调用缺少 name: id='${tc.id}'")
                return@mapNotNull null
            }
            ToolCall(id = tc.id, name = fn.name, arguments = fn.arguments)
        }
        if (!calls.isNullOrEmpty()) emit(StreamDelta(toolCalls = calls))
        parsed?.usage?.let {
            emit(StreamDelta(usage = TokenUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )))
        }
    }

    private fun buildClaudeBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList()
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        val effThinking = modelCfg?.thinking() ?: provider.thinking()
        val effMaxTokens = if (effThinking != ThinkingLevel.OFF) {
            maxOf(modelCfg?.maxTokens ?: provider.maxTokens, effThinking.budgetTokens + 1024)
        } else {
            modelCfg?.maxTokens ?: provider.maxTokens
        }
        var systemText: String? = null
        val convMsgs = mutableListOf<ChatMessage>()
        for (m in messages) {
            if (m.role == "system") {
                systemText = if (systemText.isNullOrBlank()) m.content
                else (systemText + "\n\n" + m.content)
            } else {
                convMsgs.add(m)
            }
        }
        val msgsJson = buildJsonArray {
            for (m in convMsgs) {
                add(buildJsonObject {
                    if (m.role == "tool") {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                m.toolCallId?.let { put("tool_use_id", it) }
                                put("content", m.content)
                            })
                        })
                    } else if (m.toolCalls != null && m.toolCalls.isNotEmpty()) {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            if (m.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", m.content)
                                })
                            }
                            for (tc in m.toolCalls) {
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    val input = runCatching {
                                        json.parseToJsonElement(tc.arguments.ifBlank { "{}" })
                                    }.getOrNull() ?: buildJsonObject { }
                                    put("input", input)
                                })
                            }
                        })
                    } else {
                        put("role", m.role)
                        if (m.isMultipart()) {
                            val parts = mutableListOf<JsonObject>()
                            if (m.content.isNotBlank()) {
                                parts.add(buildJsonObject {
                                    put("type", "text")
                                    put("text", m.content)
                                })
                            }
                            for (att in m.parts!!) {
                                val url = att.imageUrl?.url ?: continue
                                val match = Regex("^data:([^;]+);base64,(.+)$").matchEntire(url)
                                if (match != null) {
                                    val mime = match.groupValues[1]
                                    val data = match.groupValues[2]
                                    parts.add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", mime)
                                            put("data", data)
                                        }
                                    })
                                }
                            }
                            put("content", buildJsonArray { parts.forEach { add(it) } })
                        } else {
                            put("content", m.content)
                        }
                    }
                })
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("max_tokens", effMaxTokens)
            if (effThinking == ThinkingLevel.OFF) put("temperature", effTemp)
            put("messages", msgsJson)
            if (!systemText.isNullOrBlank()) put("system", systemText)
            if (effThinking != ThinkingLevel.OFF) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", effThinking.budgetTokens)
                }
            }
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (t in tools) {
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            val schema = runCatching {
                                json.parseToJsonElement(t.inputSchema.ifBlank { """{"type":"object"}""" })
                            }.getOrNull() ?: buildJsonObject { put("type", "object") }
                            put("input_schema", schema)
                        })
                    }
                })
            }
            for ((k, v) in provider.extraBody) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private suspend fun listClaudeModels(provider: ProviderConfig): List<String> {
        val fallback = if (claudeNeedsV1Fallback(provider.baseUrl)) {
            claudeV1ModelsEndpoint(provider.baseUrl)
        } else null
        val text = try {
            claudeGetText(provider, claudeModelsEndpoint(provider.baseUrl))
        } catch (e: ClaudeEndpoint404) {
            claudeGetText(provider, fallback ?: throw e)
        }
        val parsed = runCatching {
            json.decodeFromString(ClaudeModelsResponse.serializer(), text)
        }.getOrNull()
        val ids = parsed?.data?.map { it.id }
        if (!ids.isNullOrEmpty()) return ids.distinct().sorted()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1] }.toList().distinct().sorted()
    }

    private suspend fun claudeGetText(provider: ProviderConfig, target: String): String {
        val resp = client.get(target) {
            headers {
                for ((k, v) in claudeAuthHeaders(provider)) append(k, v)
            }
        }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrDefault("")
            val msg = "HTTP ${resp.status.value}: ${err.take(300)}"
            if (resp.status == HttpStatusCode.NotFound) throw ClaudeEndpoint404(msg)
            throw RuntimeException(msg)
        }
        return resp.bodyAsText()
    }

    /**
     * Claude 流式解析（含 tool_use）。
     *
     * ## name 的容错
     *
     * 与 OpenAI 分支保持同一套策略：
     * content_block_start 里 name 缺失或为空时，不应把已累积的值清掉。
     *
     * ## 为什么收到 message_stop 就要立刻结束读取
     *
     * message_stop 是 Claude 流式协议的结束标记，表示本轮生成完毕。
     *
     * 与 OpenAI 分支同理：部分中转在发出 message_stop 后既不关连接也不发
     * [DONE]，继续 readUTF8Line() 会阻塞到 socket 超时，
     * 表现为界面无限「正在输入」。
     *
     * 因此收到 message_stop 立即 break，同时把仍在累积中的 tool_use 一并发出
     * （正常情况下 content_block_stop 已经发过，这里是兜底）。
     */
    private suspend fun FlowCollector<StreamDelta>.readClaudeStream(
        channel: ByteReadChannel
    ) {
        var usageInput = 0
        var usageOutput = 0
        val toolAcc = LinkedHashMap<Int, Triple<String, String, StringBuilder>>()
        var sawStop = false

        while (true) {
            val raw = channel.readUTF8Line() ?: break
            if (raw.isEmpty()) continue
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val field = raw.substring(0, colon).trim()
            val data = raw.substring(colon + 1).trim()
            if (field != "data" || data.isEmpty() || data == "[DONE]") continue

            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val block = obj["content_block"]?.jsonObject ?: continue
                    if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        val cur = toolAcc[idx]
                        val id = block["id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: cur?.first ?: ""
                        val name = block["name"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: cur?.second ?: ""
                        toolAcc[idx] = Triple(id, name, cur?.third ?: StringBuilder())
                    }
                }
                "content_block_delta" -> {
                    val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val delta = obj["delta"]?.jsonObject ?: continue
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                            if (!text.isNullOrEmpty()) emit(StreamDelta(text = text))
                        }
                        "thinking_delta" -> {
                            val think = delta["thinking"]?.jsonPrimitive?.contentOrNull
                            if (!think.isNullOrEmpty()) emit(StreamDelta(reasoning = think))
                        }
                        "input_json_delta" -> {
                            val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull
                            if (!partial.isNullOrEmpty()) {
                                toolAcc[idx]?.third?.append(partial)
                            }
                        }
                    }
                }
                "content_block_stop" -> {
                    val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: -1
                    toolAcc.remove(idx)?.let { (id, name, args) ->
                        if (name.isNotBlank()) {
                            emit(StreamDelta(toolCalls = listOf(
                                ToolCall(
                                    id = id.ifBlank { "call_$idx" },
                                    name = name,
                                    arguments = args.toString().ifBlank { "{}" }
                                )
                            )))
                        } else {
                            DebugLog.e(
                                "Llm",
                                "Claude tool_use 缺少 name：idx=$idx id='$id' argsLen=${args.length}"
                            )
                        }
                    }
                }
                "message_delta" -> {
                    val u = obj["usage"]?.jsonObject
                    if (u != null) {
                        usageOutput = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: usageOutput
                        if (usageInput > 0 || usageOutput > 0) {
                            emit(StreamDelta(usage = TokenUsage(
                                promptTokens = usageInput,
                                completionTokens = usageOutput,
                                totalTokens = usageInput + usageOutput
                            )))
                        }
                    }
                }
                "message_start" -> {
                    val msg = obj["message"]?.jsonObject
                    val u = msg?.get("usage")?.jsonObject
                    if (u != null) {
                        usageInput = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: usageInput
                        usageOutput = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: usageOutput
                    }
                }
                "message_stop" -> {
                    sawStop = true
                    DebugLog.i("Llm", "收到 message_stop，主动结束流读取")
                }
                "error" -> {
                    val err = obj["error"]?.jsonObject
                    val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: obj.toString()
                    throw RuntimeException(msg.take(500))
                }
            }

            // message_stop 之后不再有任何有效事件，主动结束
            if (sawStop) break
        }

        // 兜底：仍有未发出的 tool_use（正常情况下 content_block_stop 已处理）
        if (toolAcc.isNotEmpty()) {
            val pending = toolAcc.values.filter { it.second.isNotBlank() }
            if (pending.isNotEmpty()) {
                DebugLog.w("Llm", "流结束时仍有 ${pending.size} 个未发出的 tool_use，补发")
                emit(StreamDelta(toolCalls = pending.map { (id, name, args) ->
                    ToolCall(
                        id = id.ifBlank { "call_${name.hashCode()}" },
                        name = name,
                        arguments = args.toString().ifBlank { "{}" }
                    )
                }))
            }
        }
    }

    private suspend fun FlowCollector<StreamDelta>.readClaudeNonStream(
        response: io.ktor.client.statement.HttpResponse
    ) {
        val text = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw RuntimeException("Bad Claude response")
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "error") {
            val err = obj["error"]?.jsonObject
            throw RuntimeException(err?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(300))
        }
        val contentArr: JsonArray = obj["content"]?.jsonArray ?: buildJsonArray { }
        val calls = mutableListOf<ToolCall>()
        for (block in contentArr) {
            val bObj = block.jsonObject
            when (bObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> bObj["text"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotEmpty()) emit(StreamDelta(text = it))
                }
                "thinking" -> bObj["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotEmpty()) emit(StreamDelta(reasoning = it))
                }
                "tool_use" -> {
                    val id = bObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = bObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = bObj["input"]
                    val args = input?.let {
                        json.encodeToString(JsonObject.serializer(), it.jsonObject)
                    } ?: "{}"
                    if (name.isNotBlank()) {
                        calls.add(ToolCall(id.ifBlank { "call_$name" }, name, args))
                    } else {
                        DebugLog.e("Llm", "非流式 tool_use 缺少 name：id='$id'")
                    }
                }
            }
        }
        if (calls.isNotEmpty()) emit(StreamDelta(toolCalls = calls))
        val usage = obj["usage"]?.jsonObject
        if (usage != null) {
            val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            emit(StreamDelta(usage = TokenUsage(
                promptTokens = input,
                completionTokens = output,
                totalTokens = input + output
            )))
        }
    }
}

internal fun claudeAuthHeaders(provider: ProviderConfig): List<Pair<String, String>> =
    buildList {
        if (provider.apiKey.isNotBlank()) add("x-api-key" to provider.apiKey)
        add("anthropic-version" to "2023-06-01")
        for ((k, v) in provider.customHeaders) {
            if (k.isBlank()) continue
            add(k to v)
        }
    }

internal class ClaudeEndpoint404(message: String) : RuntimeException(message)
