package com.singularity.todo.feature.ai

import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production implementation of [TextGenPort] using JetBrains Koog.
 * Currently falls back to [FakeTextGen] — Koog API integration is completed in Phase 5.
 */
class KoogAgentService(
    private val secureStorage: SecureStoragePort,
    private val settings: SettingsRepository,
) : TextGenPort {

    // TODO (Phase 5): Replace with real Koog agent:
    //   AIAgent<Unit, Unit>(
    //     promptExecutor = simpleOpenAIExecutor(apiKey, baseUrl),
    //     systemPrompt = Prompts.chatSystem,
    //     toolRegistry = toolRegistry { tools(Koin.getAll()) }
    //   )
    private val delegate: TextGenPort = FakeTextGen()

    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?
    ): Result<String> = delegate.generate(prompt, systemPrompt, model)

    fun streamChat(message: String): Flow<String> = flow {
        emit("(AI unavailable: Koog integration pending in Phase 5)")
    }
}

/**
 * In-memory fake of [TextGenPort] — always returns a placeholder response.
 */
class FakeTextGen : TextGenPort {
    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?
    ): Result<String> = Result.success(
        "(Placeholder AI response — configure API key in Settings > AI Provider to enable real AI.)"
    )
}
