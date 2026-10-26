package com.singularity.todo.feature.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentBuilder
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.ai.RecordingTool
import com.singularity.todo.core.ai.ToolCallLoop
import com.singularity.todo.core.ai.filterTools
import com.singularity.todo.core.ai.resolveModelWithFlags
import com.singularity.todo.core.observability.ToolCallRecorder
import com.singularity.todo.core.observability.ToolCallSource
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.di.PromptExecutorPort
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.OpenAiConfig
import com.singularity.todo.core.security.ProfileAwareSecureStorage
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.prompts.Prompts
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.singularity.todo.core.error.runCatchingCancellable

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
 *
 * Kill switches: tools are filtered through [RemoteConfigPort.mcpToolFlags] at construction time.
 * Models are resolved through [RemoteConfigPort.modelFlags] — disabled models fall back to GPT-4o Mini.
 */
class KoogAgentService(
    private val secureStorage: ProfileAwareSecureStorage,
    private val settings: SettingsRepository,
    private val promptExecutor: ai.koog.prompt.executor.model.PromptExecutor,
    private val streamingExecutor: PromptExecutorPort,
    allTools: List<Tool<*, *>>,
    private val remoteConfigPort: RemoteConfigPort,
    private val toolCallRecorder: ToolCallRecorder? = null,
) : TextGenPort {

    // Filter tools based on current RemoteConfig flags. Read at construction so this
    // is fixed for the lifetime of the service instance (flags refresh is infrequent).
    private val tools: List<Tool<*, *>> = run {
        val flags = remoteConfigPort.observe().value.mcpToolFlags
        filterTools(allTools, flags).also { enabled ->
            val disabled = allTools.map { it.descriptor.name } - enabled.map { it.descriptor.name }.toSet()
            if (disabled.isNotEmpty()) {
                co.touchlab.kermit.Logger.withTag("KoogAgentService")
                    .i { "Kill switch: disabled tools: $disabled" }
            }
        }
    }

    /**
     * The tools the agent may call, each wrapped so that its use is recorded.
     *
     * The wrapper is transparent — the agent sees the same name, the same description
     * and the same arguments — so recording costs the agent nothing and tells the
     * report which tools the in-app agent actually reaches for. The MCP server measures
     * at its own dispatch boundary instead; the two records share one event schema, so
     * the report reads both.
     */
    private val agentTools: ToolRegistry = ToolRegistry.builder()
        .tools(tools.map { tool -> tool.asRecorded() })
        .build()

    @Suppress("UNCHECKED_CAST")
    private fun Tool<*, *>.asRecorded(): Tool<*, *> {
        val recorder = toolCallRecorder ?: return this
        return RecordingTool(
            delegate = this as Tool<Any, Any>,
            recorder = recorder,
            source = ToolCallSource.IN_APP,
        )
    }

    /**
     * The tools the chat screen may call, in the shape the tool-call loop needs.
     *
     * The registry holds them as [ai.koog.agents.core.tools.ToolBase], because that is the
     * type the builder collects; every tool this project writes is a [Tool], and the loop
     * needs the narrower type because it calls the tool without the per-call metadata the
     * agent runtime threads through.
     */
    @Suppress("UNCHECKED_CAST")
    private val callableTools: List<Tool<*, *>> = agentTools.tools.map { it as Tool<*, *> }

    private fun createAgent(systemPrompt: String, modelId: String): AIAgent<String, String> {
        val flags = remoteConfigPort.observe().value.modelFlags
        return AIAgent.builder()
            .promptExecutor(promptExecutor)
            .systemPrompt(systemPrompt)
            .toolRegistry(agentTools)
            .llmModel(resolveModelWithFlags(modelId, flags))
            .build()
    }

    private suspend fun requireApiKey(): String? =
        secureStorage.read(OpenAiConfig.KEY_OPENAI)?.takeIf { it.isNotBlank() }

    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?,
    ): Result<String> = runCatchingCancellable {
        requireApiKey()
            ?: return@runCatchingCancellable "(AI unavailable: API key not configured.)"

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
     * Streams a chat response as it happens, letting the model call tools along the way.
     *
     * The model is offered the same tools the [AIAgent] behind [generate] is offered, and
     * the same ones the kill switch left standing. Without this, the chat screen answered
     * questions about the user's own data from the model's imagination, while the tools sat
     * there unused.
     *
     * The stream carries activity, not just text: the text as it arrives, the tool calls and
     * their outcomes, and the end of the turn. A reader can watch what the agent did.
     *
     * [instructions] is what this one call may do beyond the configured persona — the screen
     * format, for a call that may draw a screen. It is added to the persona, not swapped for
     * it: the persona is who the assistant is, and the instructions are what it is doing now.
     */
    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = flow {
        val apiKey = requireApiKey()
        if (apiKey == null) {
            // No completion event: the turn did not finish, it never started. A turn that ends
            // without one is a failed turn, and a failed turn says so — a completed turn whose
            // text mentions a missing key is a turn that reports two different things.
            emit(AgentEvent.TextDelta("(AI unavailable: API key not configured. Set it in Settings > AI Provider.)"))
            return@flow
        }

        val flags = remoteConfigPort.observe().value.modelFlags
        val configuredPrompt = settings.aiSystemPrompt.first().ifBlank { Prompts.chatSystem }
        val systemPrompt = if (instructions.isBlank()) configuredPrompt else "$configuredPrompt\n\n$instructions"
        val modelId = settings.aiModel.first().ifBlank { OpenAiConfig.DEFAULT_MODEL }
        val model = resolveModelWithFlags(modelId, flags)

        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(systemPrompt)
            user(message)
        }

        ToolCallLoop(tools = callableTools)
            .run(p) { conversation, descriptors ->
                streamingExecutor.executeStreaming(conversation, model, descriptors)
            }
            .collect { event -> emit(event) }
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = runCatchingCancellable {
        val response = httpClient.get("$baseUrl/models") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            accept(ContentType.Application.Json)
        }.bodyAsText()
        val parsed = Json.parseToJsonElement(response)
        parsed.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    }

    /**
     * Lazily built, and never closed.
     *
     * This service is a DI singleton, so the client shares the process lifetime —
     * which for the desktop app is the app's lifetime, and is the way a Ktor client is
     * meant to be owned. Building it lazily means the many tests that construct a
     * `KoogAgentService` and never list models pay nothing and leak nothing.
     *
     * It was `java.net.HttpURLConnection` before, opened and disconnected per call.
     * That has no `java.net` on a non-JVM target at all, and Ktor was already a
     * dependency for the rest of this feature.
     */
    private val httpClient: HttpClient by lazy {
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                // Stands in for `HttpURLConnection.readTimeout`: an upper bound on
                // the whole exchange, not just the socket read, which is what a user
                // refreshing a model list is actually waiting for.
                requestTimeoutMillis = 15_000
            }
        }
    }
}
