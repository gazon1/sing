package com.singularity.todo.feature.ai.usage

import ai.koog.prompt.message.ResponseMetaInfo
import com.singularity.todo.core.observability.ToolUsageEvent
import kotlinx.datetime.Instant

/**
 * Extracts token usage from Koog's [ResponseMetaInfo].
 *
 * Koog embeds usage metadata in `Message.Assistant.metaInfo` after each LLM call.
 * This helper converts it into a [ToolUsageEvent] for recording.
 *
 * Handles provider differences (OpenAI / Anthropic / Ollama) via the
 * nullable fields of [ResponseMetaInfo].
 */
object UsageExtractor {

    /**
     * Extracts usage from [metaInfo] into a [ToolUsageEvent].
     *
     * @param metaInfo Koog response metadata (may be null for local/ollama models)
     * @param toolName Human-readable tool name, e.g. "decompose_and_create"
     * @param modelId Raw model string from settings, e.g. "gpt-4o-mini"
     * @param durationMs Wall-clock time for the entire tool execution
     * @param error Error message if the call failed, null on success
     * @param profileId Profile that made the call
     * @param timestamp Instant of recording (pass `Clock.now()` from the caller)
     */
    fun extract(
        metaInfo: ResponseMetaInfo?,
        toolName: String,
        modelId: String,
        durationMs: Long,
        error: String?,
        profileId: String,
        timestamp: Instant,
    ): ToolUsageEvent {
        val inputTokens = metaInfo?.inputTokensCount ?: 0
        val outputTokens = metaInfo?.outputTokensCount ?: 0
        val totalTokens = metaInfo?.totalTokensCount ?: (inputTokens + outputTokens)

        val costMicros = com.singularity.todo.feature.ai.ModelPricingTable.priceOrNull(
            modelId, inputTokens, outputTokens
        )

        return ToolUsageEvent(
            toolName = toolName,
            modelId = modelId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            costUsdMicros = costMicros,
            durationMs = durationMs,
            profileId = profileId,
            error = error,
            timestamp = timestamp,
        )
    }

    /**
     * Creates a zero-token event for tools that don't call the LLM
     * (pure data transformations, e.g., wikilink resolution).
     */
    fun forDataTool(toolName: String, profileId: String, timestamp: Instant): ToolUsageEvent {
        return ToolUsageEvent(
            toolName = toolName,
            modelId = "n/a",
            inputTokens = 0,
            outputTokens = 0,
            totalTokens = 0,
            costUsdMicros = null,
            durationMs = 0,
            profileId = profileId,
            error = null,
            timestamp = timestamp,
        )
    }
}
