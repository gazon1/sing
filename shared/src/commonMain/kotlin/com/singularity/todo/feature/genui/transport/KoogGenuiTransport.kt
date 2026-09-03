package com.singularity.todo.feature.genui.transport

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.di.PromptExecutorPort
import com.singularity.todo.feature.genui.catalog.BasicCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production [GenuiTransport] backed by Koog [PromptExecutorPort].
 *
 * Builds a prompt that includes the [BasicCatalog] system appendix,
 * streams token deltas, and emits JSON-Lines for [A2uiParser].
 */
class KoogGenuiTransport(
    private val promptExecutor: PromptExecutorPort,
    private val model: LLModel = OpenAIModels.Chat.GPT4oMini,
) : GenuiTransport {

    override suspend fun send(prompt: String, systemPrompt: String): Flow<String> = flow {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system("$systemPrompt\n\n${BasicCatalog.systemPromptAppendix}")
            user(prompt)
        }
        promptExecutor.executeStreaming(p, model, emptyList())
            .collect { frame ->
                // Emit text deltas as they're received (A2uiParser.parseLine handles buffering)
                when (frame) {
                    is StreamFrame.TextDelta -> emit(frame.text)
                    else -> { /* skip reasoning/tool tokens */ }
                }
            }
    }
}
