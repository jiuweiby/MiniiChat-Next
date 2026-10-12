package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ChatPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
) {
    @Serializable
    data class ImageUrl(val url: String, val detail: String? = null)
}

/**
 * LLM 侧的一条对话消息
 *
 * [toolCalls]        助手发起的工具调用列表（非空时给 OpenAI 传 tool_calls 字段）
 * [toolCallId]       当本消息是 tool 角色时，指向对应的 tool_call id
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val parts: List<ChatPart>? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
) {
    fun isMultipart(): Boolean = !parts.isNullOrEmpty()

    companion object {
        fun toolResult(toolCallId: String, content: String) =
            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
    }
}

/** 一次工具调用请求（来自模型） */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/** 传给模型的工具描述 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 字符串 */
    val inputSchema: String
)

data class StreamDelta(
    val text: String = "",
    val reasoning: String = "",
    val usage: TokenUsage? = null,
    /** 本 chunk 内新发起 / 累积的工具调用（增量合并见 LlmClient） */
    val toolCalls: List<ToolCall> = emptyList()
)

@Serializable
internal data class ChatChunk(
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val delta: Delta? = null,
        /**
         * 非标准流式响应里可能出现完整 message。
         *
         * 部分中转或自建网关在 stream=true 时并不发送标准 delta 帧，
         * 而是在某些 chunk 里直接给出完整的 assistant message（含 tool_calls）。
         * 旧实现的解析分支只读 delta，遇到这种响应会完全拿不到工具调用，
         * 表现为「模型明明调用了工具，但工具名是空字符串」。
         */
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        val role: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null
    )

    @Serializable
    data class ToolCallDelta(
        val index: Int = 0,
        val id: String? = null,
        val type: String? = null,
        val function: FunctionDelta? = null
    )

    @Serializable
    data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null
    )

    @Serializable
    data class ResponseMessage(
        val role: String = "assistant",
        val content: String = "",
        @SerialName("tool_calls") val toolCalls: List<ToolCallFull>? = null
    )

    @Serializable
    data class ToolCallFull(
        val id: String = "",
        val type: String = "function",
        val function: FunctionFull? = null
    )

    @Serializable
    data class FunctionFull(
        val name: String = "",
        val arguments: String = ""
    )

    @Serializable
    data class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0
    )
}

@Serializable
internal data class ChatResponse(
    val choices: List<ChatChunk.Choice> = emptyList(),
    val usage: ChatChunk.UsageDto? = null
)

@Serializable
internal data class ModelEntry(val id: String)

@Serializable
internal data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
internal data class ClaudeModelEntry(
    val id: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
internal data class ClaudeModelsResponse(val data: List<ClaudeModelEntry> = emptyList())
