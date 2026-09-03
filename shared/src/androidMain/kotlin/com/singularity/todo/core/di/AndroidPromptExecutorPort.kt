package com.singularity.todo.core.di

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf


/**
 * Android stub for [PromptExecutorPort].
 *
 * On Android, AI features are not available — [KoogAgentService] is never invoked
 * on mobile (no AI Chat tab). This stub exists only so the DI graph stays complete.
 *
 * @throws UnsupportedOperationException always, since AI is unavailable on Android.
 */
class StubPromptExecutorPort : PromptExecutorPort {

    private val unavailableText = "(AI unavailable on Android. Use the desktop/JVM target.)"

    override suspend fun execute(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = throw UnsupportedOperationException(unavailableText)

    override fun executeStreaming(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flowOf(StreamFrame.TextDelta(unavailableText))
}

/**
 * Android actual for [createKoogPromptExecutor].
 */
actual fun createKoogPromptExecutor(): PromptExecutorPort = StubPromptExecutorPort()
