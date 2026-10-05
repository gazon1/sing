package com.singularity.todo.feature.genui.transport

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.di.PromptExecutorPort
import com.singularity.todo.feature.ai.KnownModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production transport backed by Koog [PromptExecutorPort].
 *
 * The system prompt arrives complete: this class does not append anything to it. A transport that
 * edits the system prompt is a second place the component vocabulary is described, and the
 * vocabulary now lives in one declaration that the prompt is generated from — so the caller
 * (see [com.singularity.todo.feature.genui.engine.GenuiSession]) composes it once.
 *
 * Emitted values are **token fragments, not lines**, and the consumer is responsible for assembling
 * them into complete messages.
 */
class KoogGenuiTransport(
    private val promptExecutor: PromptExecutorPort,
    private val model: LLModel = KnownModels.GPT4oMini,
) : BaseGenuiTransport {

    override suspend fun send(prompt: String, systemPrompt: String): Flow<String> = flow {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(systemPrompt)
            user(prompt)
        }
        promptExecutor.executeStreaming(p, model, emptyList())
            .collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> emit(frame.text)
                    else -> { /* skip reasoning/tool tokens */ }
                }
            }
    }
}
