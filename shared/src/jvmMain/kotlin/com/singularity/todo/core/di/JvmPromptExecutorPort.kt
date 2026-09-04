package com.singularity.todo.core.di

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * JVM adapter wrapping a Koog [ai.koog.prompt.executor.model.PromptExecutor]
 * behind the platform-agnostic [PromptExecutorPort] interface.
 */
class JvmPromptExecutorPort(
    val executor: ai.koog.prompt.executor.model.PromptExecutor,
) : PromptExecutorPort {

    // PromptExecutor.execute() is a suspend function — call it inside runBlocking
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
