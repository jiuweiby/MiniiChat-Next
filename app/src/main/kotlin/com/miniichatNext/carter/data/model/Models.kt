package com.miniichatNext.carter.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
    const val TOOL = "tool"
}

object ToolInvocationState {
    const val PENDING_APPROVAL = "pending_approval"
    const val EXECUTING = "executing"
    const val RUNNING = "executing"
    const val SUCCESS = "success"
    const val ERROR = "error"
    const val REJECTED = "rejected"
}

@Serializable
data class Attachment(
    val type: String,
    val uri: String,
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0
)

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0,
    val tokensPerSecond: Float = 0f
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        durationMs = durationMs + other.durationMs,
        tokensPerSecond = if (durationMs + other.durationMs > 0)
            ((completionTokens + other.completionTokens) * 1000f) / (durationMs + other.durationMs)
        else 0f
    )
}

/**
 * 消息内记录的一次工具调用与执行结果
 */
@Serializable
data class ToolInvocation(
    val callId: String,
    val toolName: String,
    val arguments: String = "",
    val state: String = ToolInvocationState.EXECUTING,
    val result: String = "",
    val error: String? = null
) {
    val argumentsJson: String get() = arguments
    val resultOutput: String get() = result
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val usage: TokenUsage? = null,
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val reasoningContent: String? = null,
    val toolCallId: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val imageUri: String?
        get() = attachments.firstOrNull { it.type == "image" }?.uri
}

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新会话",
    val assistantId: String = "default",
    val messages: List<Message> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val suggestionSeed: Int = 0,
    val suggestionPages: Map<Int, List<String>> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
