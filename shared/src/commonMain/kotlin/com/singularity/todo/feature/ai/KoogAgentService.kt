package com.singularity.todo.feature.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentBuilder
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.di.PromptExecutorPort
import com.singularity.todo.core.security.ProfileAwareSecureStorage
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production [TextGenPort] backed by JetBrains Koog [AIAgent].
 *
 * Uses [AIAgentBuilder] to create a simple chat agent with:
 * - [functionalStrategy] for the reasoning loop
 * - [PromptExecutor] wired to OpenAI via the settings
 * - System prompt from [Prompts.chatSystem]
 *
 * Returns `(AI unavailable: ...)` when the API key is not configured — never
 * throws, since failures inside the AI loop are surfaced through [Result].
 */
class KoogAgentService(
    private val secureStorage: ProfileAwareSecureStorage,
    private val settings: SettingsRepository,
    private val promptExecutor: ai.koog.prompt.executor.model.PromptExecutor,
    private val streamingExecutor: PromptExecutorPort,
    private val tools: List<Tool<*, *>>,
) : TextGenPort {

    private val agentTools: ToolRegistry = ToolRegistry.builder().tools(tools).build()

    private fun createAgent(systemPrompt: String, modelId: String): AIAgent<String, String> =
        AIAgent.builder()
            .promptExecutor(promptExecutor)
            .systemPrompt(systemPrompt)
            .toolRegistry(agentTools)
            .llmModel(resolveModel(modelId))
            .build()

    private suspend fun requireApiKey(): String? =
        secureStorage.read(OpenAiConfig.KEY_OPENAI)?.takeIf { it.isNotBlank() }

    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?,
    ): Result<String> = runCatching {
        val apiKey = requireApiKey()
            ?: return@runCatching "(AI unavailable: API key not configured.)"

        val effectiveSystemPrompt = systemPrompt
            ?: settings.aiSystemPrompt.first().ifBlank { Prompts.chatSystem }
        val effectiveModel = model
            ?: settings.aiModel.first().ifBlank { OpenAiConfig.DEFAULT_MODEL }

        val agent = createAgent(effectiveSystemPrompt, effectiveModel)
        try {
            agent.run(prompt)
        } finally {
            agent.close()
        }
    }

    /**
     * Streams chat responses token-by-token using [PromptExecutorPort.executeStreaming].
     */
    override fun streamChat(message: String): Flow<String> = flow {
        val apiKey = requireApiKey()
        if (apiKey == null) {
            emit("(AI unavailable: API key not configured. Set it in Settings > AI Provider.)")
            return@flow
        }

        val systemPrompt = settings.aiSystemPrompt.first().ifBlank { Prompts.chatSystem }
        val modelId = settings.aiModel.first().ifBlank { OpenAiConfig.DEFAULT_MODEL }
        val model = resolveModel(modelId)

        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(systemPrompt)
            user(message)
        }

        streamingExecutor.executeStreaming(p, model, emptyList())
            .collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> emit(frame.text)
                    is StreamFrame.ReasoningDelta -> { /* skip reasoning tokens */ }
                    is StreamFrame.ToolCallDelta -> { /* skip tool call tokens */ }
                    is StreamFrame.End -> { /* stream complete */ }
                    is StreamFrame.TextComplete -> { /* final text, already emitted via deltas */ }
                    is StreamFrame.ReasoningComplete -> { /* skip */ }
                    is StreamFrame.ToolCallComplete -> { /* skip */ }
                }
            }
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        runCatching {
            val url = URL("$baseUrl/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                val response = conn.inputStream.bufferedReader().readText()
                val parsed = Json.parseToJsonElement(response)
                parsed.jsonArray
                    .map { it.jsonObject["id"]?.jsonPrimitive?.content }
                    .filterNotNull()
            } finally {
                conn.disconnect()
            }
        }
}

/** Maps a user-supplied model identifier to a Koog [LLModel] constant. */
internal fun resolveModel(modelId: String): LLModel = when (modelId) {
    "gpt-4o" -> KnownModels.GPT4o
    "gpt-4o-mini" -> KnownModels.GPT4oMini
    "gpt-4.1" -> KnownModels.GPT4_1
    "gpt-4.1-nano" -> KnownModels.GPT4_1Nano
    "gpt-4.1-mini" -> KnownModels.GPT4_1Mini
    "o1" -> KnownModels.O1
    "o3" -> KnownModels.O3
    "o3-mini" -> KnownModels.O3Mini
    "o4-mini" -> KnownModels.O4Mini
    "gpt-5" -> KnownModels.GPT5
    "gpt-5-mini" -> KnownModels.GPT5Mini
    else -> KnownModels.GPT4oMini
}

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
        kotlinx.coroutines.flow.flowOf(success)

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        Result.success(emptyList())
}