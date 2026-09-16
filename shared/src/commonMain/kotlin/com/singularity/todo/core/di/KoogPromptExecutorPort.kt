package com.singularity.todo.core.di

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/**
 * Common adapter wrapping a Koog [ai.koog.prompt.executor.model.PromptExecutor]
 * behind the platform-agnostic [PromptExecutorPort] interface.
 *
 * The [executor] is exposed publicly so platform DI modules can re-bind it
 * as a singleton for AI tool factories that need the raw Koog API.
 */
class KoogPromptExecutorPort(override val executor: ai.koog.prompt.executor.model.PromptExecutor) :
    PromptExecutorPort {

    override suspend fun execute(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = executor.execute(prompt, model, tools)

    override fun executeStreaming(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = executor.executeStreaming(prompt, model, tools)
}
