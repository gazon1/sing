package com.singularity.todo.feature.ai

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.settings.SettingsRepository

class RefineTaskUseCase(
    private val openAi: OpenAiClient,
    private val settingsRepository: SettingsRepository
) {
    private val systemPrompt = """
        You are a productivity assistant. Rewrite the user's task title to be clearer,
        more actionable, and specific. Return only the rewritten title, no quotes.
    """.trimIndent()

    suspend operator fun invoke(title: String): Result<String> = runCatchingResult {
        require(title.isNotBlank()) { throw AppError.Validation("Title cannot be blank") }

        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = "Title: $title")
        )

        val model = settingsRepository.aiModelBlocking()
        openAi.chatCompletion(messages, model).getOrThrow()
    }
}
