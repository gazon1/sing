package com.singularity.todo.feature.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentBuilder
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Production [TextGenPort] backed by JetBrains Koog [AIAgent].
 *
 * Uses [AIAgentBuilder] to create a simple chat agent with:
 * - [functionalStrategy] for the reasoning loop
 * - [PromptExecutor] wired to OpenAI via the settings
 * - System prompt from [Prompts.chatSystem]
 *
 * Falls back to [FakeTextGen] when the API key is not configured.
 */
class KoogAgentService(
    private val secureStorage: SecureStoragePort,
    private val settings: SettingsRepository,
    private val promptExecutor: PromptExecutor,
) : TextGenPort {

    /**
     * Creates a fresh [AIAgent] for each request.
     * The agent uses a simple loop strategy: call LLM → execute tools → send results → repeat.
     */
    private fun createAgent(systemPrompt: String, model: String): AIAgent<String, String> {
        val resolvedModel = resolveModel(model)
        return AIAgent.builder()
            .promptExecutor(promptExecutor)
            .systemPrompt(systemPrompt)
            .toolRegistry(agentTools)
            .build()
    }

    private fun resolveModel(modelId: String): LLModel {
        return when (modelId) {
            "gpt-4o" -> OpenAIModels.Chat.GPT4o
            "gpt-4o-mini" -> OpenAIModels.Chat.GPT4oMini
            "gpt-4.1" -> OpenAIModels.Chat.GPT4_1
            "gpt-4.1-nano" -> OpenAIModels.Chat.GPT4_1Nano
            "gpt-4.1-mini" -> OpenAIModels.Chat.GPT4_1Mini
            "o1" -> OpenAIModels.Chat.O1
            "o3" -> OpenAIModels.Chat.O3
            "o3-mini" -> OpenAIModels.Chat.O3Mini
            "o4-mini" -> OpenAIModels.Chat.O4Mini
            "gpt-5" -> OpenAIModels.Chat.GPT5
            "gpt-5-mini" -> OpenAIModels.Chat.GPT5Mini
            else -> OpenAIModels.Chat.GPT4oMini
        }
    }

    private val agentTools: ToolRegistry = ToolRegistry.EMPTY

    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?
    ): Result<String> = runCatching {
        val apiKey = secureStorage.read("ai_key_openai").orEmpty()
        if (apiKey.isBlank()) return@runCatching "(AI unavailable: API key not configured.)"

        val effectiveSystemPrompt = systemPrompt ?: settings.aiSystemPrompt.first().ifBlank { Prompts.chatSystem }
        val effectiveModel = model ?: settings.aiModel.first().ifBlank { "gpt-4o-mini" }

        val agent = createAgent(effectiveSystemPrompt, effectiveModel)
        try {
            agent.run(prompt)
        } finally {
            agent.close()
        }
    }

    /**
     * Streams chat responses. Each message creates a fresh agent session.
     */
    fun streamChat(message: String): Flow<String> = flow {
        val apiKey = secureStorage.read("ai_key_openai").orEmpty()
        if (apiKey.isBlank()) {
            emit("(AI unavailable: API key not configured. Set it in Settings > AI Provider.)")
            return@flow
        }

        val systemPrompt = settings.aiSystemPrompt.first().ifBlank { Prompts.chatSystem }
        val model = settings.aiModel.first().ifBlank { "gpt-4o-mini" }
        val agent = createAgent(systemPrompt, model)

        try {
            val result = agent.run(message)
            emit(result)
        } finally {
            agent.close()
        }
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
