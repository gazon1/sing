package com.singularity.todo.core.observability

import kotlin.time.Instant

/**
 * One-shot event emitted after each AI tool call (successful or failed).
 * Carries token counts, latency, model, and cost.
 */
data class ToolUsageEvent(
    val toolName: String, // e.g. "decompose_and_create"
    val modelId: String, // e.g. "gpt-4o-mini"
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costUsdMicros: Long?, // null if model not in pricing table
    val durationMs: Long,
    val profileId: String, // from ProfileAwareCurrentUser
    val error: String?, // null on success
    val timestamp: Instant, // from Clock.System.now()
)

/** Daily aggregated usage. */
data class DailyUsage(
    val date: String, // "2026-09-07"
    val totalTokens: Long,
    val totalCostUsdMicros: Long?,
    val requestCount: Long,
)

/** Per-tool aggregated usage. */
data class ToolUsage(val toolName: String, val totalTokens: Long, val totalCostUsdMicros: Long?, val callCount: Long)

/** Per-model aggregated usage. */
data class ModelUsage(val modelId: String, val totalTokens: Long, val totalCostUsdMicros: Long?, val callCount: Long)
