package com.singularity.todo.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fake of [TextGenPort].
 *
 * Default behaviour returns a placeholder "AI unavailable" message — used on
 * platforms where the AI layer is not wired. Tests can override [success],
 * [failureMessage], or supply a [generateHandler] for full control. Pass
 * [trackGenerateCalls] = `true` to capture invocations.
 */
class FakeTextGen(
    private val success: String = "(Placeholder AI response — configure API key in Settings > AI Provider to enable real AI.)",
    private val failureMessage: String? = null,
    private val trackGenerateCalls: Boolean = false,
) : TextGenPort {
    private val _generateCalls = mutableListOf<Triple<String, String?, String?>>()

    /** Captured [generate] calls when [trackGenerateCalls] is enabled. */
    val generateCalls: List<Triple<String, String?, String?>> get() = _generateCalls

    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?,
    ): Result<String> {
        if (trackGenerateCalls) _generateCalls += Triple(prompt, systemPrompt, model)
        return failureMessage?.let { Result.failure(RuntimeException(it)) } ?: Result.success(success)
    }

    override fun streamChat(message: String): Flow<String> =
        flowOf(success)

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        Result.success(emptyList())
}
