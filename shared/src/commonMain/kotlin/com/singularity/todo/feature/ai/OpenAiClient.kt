package com.singularity.todo.feature.ai

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OpenAiClient(
    private val settingsRepository: SettingsRepository
) {
    private val apiKey: String by lazy { settingsRepository.aiApiKeyBlocking().orEmpty() }

    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        model: String = "gpt-4o-mini"
    ): Result<String> = runCatchingResult {
        require(apiKey.isNotBlank()) { throw AppError.Validation("API key not configured") }
        // TODO: Implement actual OpenAI API call once we verify the correct v4.x API
        // The aallam/openai-kotlin v4.x has breaking API changes
        throw AppError.Network(UnsupportedOperationException("OpenAI client needs migration to v4.x API"))
    }

    fun chatStream(
        messages: List<ChatMessage>,
        model: String = "gpt-4o-mini"
    ): Flow<String> = flow {
        chatCompletion(messages, model).getOrThrow()
    }
}

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatMessageData(
    val role: String,
    val content: String
)
