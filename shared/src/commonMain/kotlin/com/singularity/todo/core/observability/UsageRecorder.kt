package com.singularity.todo.core.observability

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * One-shot event emitted after each AI tool call (successful or failed).
 * Carries token counts, latency, model, and cost.
 */
data class ToolUsageEvent(
    val toolName: String, // e.g., "decompose_and_create"
    val modelId: String, // e.g., "gpt-4o-mini"
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costUsdMicros: Long?, // null if model not in pricing table
    val durationMs: Long,
    val profileId: String, // from CurrentUser.profileId
    val error: String?, // null on success
    val timestamp: Instant,
)

/** Daily aggregated usage. */
data class DailyUsage(
    val date: String, // "2026-09-07"
    val totalTokens: Long,
    val totalCostUsdMicros: Long?,
    val requestCount: Long,
)

/** Per-tool aggregated usage. */
data class ToolUsage(
    val toolName: String,
    val totalTokens: Long,
    val totalCostUsdMicros: Long?,
    val callCount: Long,
)

/** Per-model aggregated usage. */
data class ModelUsage(
    val modelId: String,
    val totalTokens: Long,
    val totalCostUsdMicros: Long?,
    val callCount: Long,
)

/**
 * Port for recording and observing AI token usage.
 *
 * Implementations persist to Room ([LlmUsageEntity]) or an external observability backend.
 * Call [record] after every AI tool execution, and query via observe* flows.
 */
interface UsageRecorder {
    /** Record a single tool invocation event. */
    suspend fun record(event: ToolUsageEvent)

    /** Most recent [limit] events for a profile, newest first. */
    fun observeRecent(profileId: String, limit: Int = 100): Flow<List<ToolUsageEvent>>

    /** Daily usage for the past [days] days. */
    fun observeByDay(profileId: String, days: Int = 30): Flow<List<DailyUsage>>

    /** Per-tool aggregation for a profile. */
    fun observeByTool(profileId: String): Flow<List<ToolUsage>>

    /** Per-model aggregation for a profile. */
    fun observeByModel(profileId: String): Flow<List<ModelUsage>>

    /** Delete records older than [olderThanDays] days. */
    suspend fun prune(olderThanDays: Int = 90)
}
