package com.antisdvg.data.remote

import com.google.gson.annotations.SerializedName

/**
 * DeepSeek chat-completions request/response DTOs.
 * Endpoint: POST https://api.deepseek.com/chat/completions
 */
data class ChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    @SerializedName("max_tokens") val maxTokens: Int = 800,
    val stream: Boolean = false
)

data class ChatMessage(
    val role: String, // "system" | "user"
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>? = null,
    val error: ApiError? = null
)

data class Choice(
    val message: ChatMessage? = null
)

data class ApiError(
    val message: String? = null
)
