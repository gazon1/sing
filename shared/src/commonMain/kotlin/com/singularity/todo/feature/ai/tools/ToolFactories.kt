package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import co.touchlab.kermit.Logger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Factory functions for creating Koog [SimpleTool] instances.
 *
 * Instead of one class file per tool, use these inline factories.
 * The DI module wires them directly.
 */

/**
 * Creates an LLM-powered tool that calls [PromptExecutor] with a system + user prompt
 * and parses the text response into [O].
 *
 * Usage:
 * ```
 * factory { llmTool<RefineTaskInput, RefineTaskOutput>(
 *     name = "refine_task",
 *     description = "...",
 *     systemPrompt = Prompts.refineSystem,
 *     userPrompt = { args: RefineTaskInput -> Prompts.refineUser(args.currentTitle, args.description) },
 *     outputSerializer = RefineTaskOutput.serializer(),
 * ) }
 * ```
 */
@PublishedApi internal val logger = Logger.withTag("ToolFactories")

inline fun <reified I : @Serializable Any, reified O : @Serializable Any> llmTool(
    name: String,
    description: String,
    systemPrompt: String,
    noinline userPrompt: (I) -> String,
    outputSerializer: KSerializer<O>,
    noinline outputBlock: (String) -> O = { text -> Json.decodeFromString(text) },
): (PromptExecutor, LLModel) -> SimpleTool<I> = { promptExecutor, model ->
    object : SimpleTool<I>(TypeToken.of(I::class.java), name, description) {
        override suspend fun execute(args: I): String {
            val p = prompt(Prompt.Empty, KoogClock.System) {
                system(systemPrompt)
                user(userPrompt(args))
            }
            val response = promptExecutor.execute(p, model, emptyList())
            val text = extractText(response)
            return try {
                Json.encodeToString(outputSerializer, outputBlock(text))
            } catch (e: Exception) {
                logger.w(e) { "failed" }
                // Fallback: try direct decode
                Json.encodeToString(outputSerializer, outputBlock(text))
            }
        }

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}

/**
 * Creates a data-only tool that reads from repositories — no LLM call.
 *
 * Usage:
 * ```
 * factory { dataTool<GetTaskInput, GetTaskOutput>(
 *     name = "get_task",
 *     description = "...",
 *     block = { args -> ... return Json.encodeToString(...) }
 * ) }
 * ```
 */
inline fun <reified I : @Serializable Any, reified O : @Serializable Any> dataTool(
    name: String,
    description: String,
    noinline block: suspend (I) -> String,
): () -> SimpleTool<I> = {
    object : SimpleTool<I>(TypeToken.of(I::class.java), name, description) {
        override suspend fun execute(args: I): String = block(args)
    }
}

// ─── Pre-built LLM tool factories ──────────────────────────────────────────────

// ─── Data tool factories ────────────────────────────────────────────────────────
