package com.singularity.todo.feature.genui.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake [GenuiTransport] for tests and local development.
 * Emits pre-configured JSON lines one at a time.
 */
class InMemoryTransport(private val responses: List<String>) : GenuiTransport {
    override suspend fun send(prompt: String, systemPrompt: String): Flow<String> = flowOf(responses.joinToString("\n"))
}
