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
     * Streams a chat response token-by-token.
     * Returns a [Flow] of string chunks. Default impl delegates to [generate].
     */
    fun streamChat(message: String): Flow<String>

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
