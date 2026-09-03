package com.singularity.todo.core.di

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic abstraction over Koog's [ai.koog.prompt.executor.model.PromptExecutor].
 *
 * Exposes only what [com.singularity.todo.feature.ai.KoogAgentService] needs.
 * JVM provides the real Koog executor; Android returns a no-op stub.
 *
 * Implementations must be thread-safe.
 */
interface PromptExecutorPort {
    /**
     * Executes a prompt and returns a complete response.
     */
    suspend fun execute(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant

    /**
     * Streams token deltas from the model.
     */
    fun executeStreaming(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame>
}
