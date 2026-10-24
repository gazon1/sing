package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Clock

/**
 * A Koog [PromptExecutor] that answers with a fixed text.
 *
 * ## Why the fake exists
 *
 * `decompose_and_create` asks a model to break a task into sub-tasks. A test that
 * wants to know what the tool does with the plan has to hand it a plan, and a real
 * executor would hand it a network call, an API key and an answer that differs on
 * every run. So the executor is replaced by one that answers from memory, and the
 * plan the test wants is the plan the tool gets.
 *
 * ## What the fake does not do
 *
 * It does not stream and it does not moderate: the tool calls neither, and a fake
 * that pretended to would be a second thing to keep honest.
 */
class FakePlanExecutor(private val answer: String, private val failure: Throwable? = null) : PromptExecutor() {

    /**
     * The answer the model "gave".
     *
     * @throws Throwable the failure the fake was built with, when it has one. The
     *   tool is expected to degrade rather than propagate it, and a test needs to be
     *   able to hand it a broken model.
     */
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        failure?.let { throw it }
        return Message.Assistant(
            parts = listOf(MessagePart.Text(answer)),
            metaInfo = ResponseMetaInfo(timestamp = Clock.System.now()),
        )
    }

    /** The tool does not stream, so there is nothing to stream. */
    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = emptyFlow()

    /** The tool does not moderate, so there is nothing to moderate. */
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() { /* no-op */ }

    companion object {
        /**
         * A model the fake answers for.
         *
         * The provider is never contacted — the tool only carries the model into the
         * prompt — so the id is a name and nothing more.
         */
        val TEST_MODEL: LLModel = LLModel(LLMProvider.OpenAI, "test-model", emptyList(), null, null)
    }
}
