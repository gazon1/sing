package com.singularity.todo.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * Port for text generation (LLM completion).
 *
 * Abstracts the underlying AI provider so tools are testable without mocking Koog internals.
 * Implementations: KoogAgentService (production), FakeTextGen (tests).
 */
interface TextGenPort {
    /**
     * Generates a text response to [prompt].
     * [systemPrompt] is prepended as a system message.
     *
     * Returns a [Flow] of string chunks (streaming), or a single result.
     */
    suspend fun generate(prompt: String, systemPrompt: String? = null, model: String? = null): Result<String>

    /**
     * Streams a chat response as it happens.
     *
     * Returns a [Flow] of [AgentEvent]s — the text as it arrives, the tool calls and their
     * outcomes, the plan, and the end of the turn. One turn is one flow.
     *
     * ## Who decides how the turn ended
     *
     * The flow, not the caller. A flow that ends **without** [AgentEvent.TurnCompleted]
     * means the turn did not finish successfully: the provider died, the transport broke,
     * or the flow was cancelled. A caller that treats the end of the flow as success will
     * tell the user the agent answered when it did not.
     *
     * [AgentEvent.TurnCompleted] marks a successful end. Everything else — failure,
     * cancellation — is a terminal outcome on the turn's state, not an event in the flow.
     *
     * @param instructions what this call may do, beyond what the configured persona says. It
     *   belongs to the call and not to the port: a turn that is not about drawing a screen is
     *   not told about screens, and a turn that is told about them is told once, here.
     */
    fun streamChat(message: String, instructions: String = ""): Flow<AgentEvent>

    /**
     * Fetches available models from the configured API endpoint.
     * Calls `GET <baseUrl>/models` with Bearer auth.
     * Returns a list of model IDs on success.
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>>
}

/**
 * Shown when an AI action is invoked but no provider is wired for the platform.
 *
 * Deliberately does NOT name a platform. The use cases are `null` on Android because
 * `aiToolsModule()` binds an error stub there — but they are equally null in a JVM test
 * or a Desktop build whose key was never configured. "AI not available on Android" told
 * a Desktop user running the same build something false; the honest statement is that
 * the provider is not configured, which is also the actionable one.
 */
const val AI_NOT_CONFIGURED = "AI provider is not configured"
