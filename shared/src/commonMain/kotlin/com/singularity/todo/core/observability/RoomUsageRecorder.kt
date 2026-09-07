package com.singularity.todo.core.observability

import com.singularity.todo.core.database.DailyUsageRow
import com.singularity.todo.core.database.LlmUsageDao
import com.singularity.todo.core.database.LlmUsageEntity
import com.singularity.todo.core.database.ModelUsageRow
import com.singularity.todo.core.database.ToolUsageRow
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * Room-based [UsageRecorder] implementation.
 * Persists [ToolUsageEvent] rows to the [LlmUsageDao] and exposes aggregated flows.
 */
class RoomUsageRecorder(
    private val llmUsageDao: LlmUsageDao,
    private val clock: Clock,
) : UsageRecorder {

    override suspend fun record(event: ToolUsageEvent) {
        val entity = LlmUsageEntity(
            id = "${event.profileId}_${event.toolName}_${event.timestamp.epochSeconds}_${(0..9999).random()}",
            profileId = event.profileId,
            toolName = event.toolName,
            modelId = event.modelId,
            inputTokens = event.inputTokens,
            outputTokens = event.outputTokens,
            totalTokens = event.totalTokens,
            costUsdMicros = event.costUsdMicros,
            durationMs = event.durationMs,
            createdAt = instantToEpochMillis(event.timestamp),
            error = event.error,
        )
        llmUsageDao.upsert(entity)
    }

    override fun observeRecent(profileId: String, limit: Int): Flow<List<ToolUsageEvent>> {
        return llmUsageDao.observeRecent(profileId, limit).map { rows ->
            rows.map { it.toEvent() }
        }
    }

    override fun observeByDay(profileId: String, days: Int): Flow<List<DailyUsage>> {
        val sinceEpochMs = instantToEpochMillis(clock.now())
            .minus(days.toLong() * 86_400_000)
        return llmUsageDao.observeByDay(profileId, sinceEpochMs).map { rows ->
            rows.map { it.toDailyUsage() }
        }
    }

    override fun observeByTool(profileId: String): Flow<List<ToolUsage>> {
        return llmUsageDao.observeByTool(profileId).map { rows ->
            rows.map { it.toToolUsage() }
        }
    }

    override fun observeByModel(profileId: String): Flow<List<ModelUsage>> {
        return llmUsageDao.observeByModel(profileId).map { rows ->
            rows.map { it.toModelUsage() }
        }
    }

    override suspend fun prune(olderThanDays: Int) {
        val cutoff = instantToEpochMillis(clock.now())
            .minus(olderThanDays.toLong() * 86_400_000)
        llmUsageDao.pruneOlderThan(cutoff)
    }

    // ─── Mapping helpers ───────────────────────────────────────────────────────

    private fun LlmUsageEntity.toEvent(): ToolUsageEvent = ToolUsageEvent(
        toolName = toolName,
        modelId = modelId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        costUsdMicros = costUsdMicros,
        durationMs = durationMs,
        profileId = profileId,
        error = error,
        timestamp = Instant.fromEpochMilliseconds(createdAt),
    )

    private fun DailyUsageRow.toDailyUsage(): DailyUsage = DailyUsage(
        date = date,
        totalTokens = totalTokens,
        totalCostUsdMicros = totalCostMicros,
        requestCount = callCount,
    )

    private fun ToolUsageRow.toToolUsage(): ToolUsage = ToolUsage(
        toolName = toolName,
        totalTokens = totalTokens,
        totalCostUsdMicros = totalCostMicros,
        callCount = callCount,
    )

    private fun ModelUsageRow.toModelUsage(): ModelUsage = ModelUsage(
        modelId = modelId,
        totalTokens = totalTokens,
        totalCostUsdMicros = totalCostMicros,
        callCount = callCount,
    )
}

/** Converts a kotlinx-datetime [Instant] to epoch milliseconds. */
private fun instantToEpochMillis(instant: kotlinx.datetime.Instant): Long =
    instant.toEpochMilliseconds()
