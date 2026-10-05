package com.singularity.todo.feature.genui.transport

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over LLM providers for the GenUI engine.
 *
 * Implementations translate a prompt string into a stream of text chunks
 * that [com.singularity.todo.feature.genui.parser.A2uiParser] can digest.
 *
 * Test fakes implement this directly (no Koog dependency needed), making
 * the parser and controller trivially testable without mocks.
 */
interface GenuiTransport {
    /**
     * Sends [prompt] to the LLM with [systemPrompt] and returns a stream
     * of text chunks (JSON-Lines, one object per line).
     */
    suspend fun send(prompt: String, systemPrompt: String): Flow<String>
}

/**
 * The platform's model connection, before usage is accounted for.
 *
 * A distinct type from [GenuiTransport] so that the decorated transport and the one it wraps can
 * both be bindings without either overriding the other. Registering the raw provider as
 * [GenuiTransport] and the recording wrapper under the same key would make the result depend on
 * which module was added last, which is not a property anyone should have to know.
 */
interface BaseGenuiTransport : GenuiTransport
