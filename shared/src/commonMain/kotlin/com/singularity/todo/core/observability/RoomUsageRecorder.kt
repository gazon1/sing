@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.core.observability

import com.singularity.todo.core.database.DailyUsageRow
import com.singularity.todo.core.database.LlmUsageDao
import com.singularity.todo.core.database.LlmUsageEntity
import com.singularity.todo.core.database.ModelUsageRow
import com.singularity.todo.core.database.ToolUsageRow
import com.singularity.todo.core.platform.TimeConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Room-based AI usage recorder.
 * Persists [ToolUsageEvent] rows to the [LlmUsageDao] and exposes aggregated flows.
 *
 * ## Clock contract
 * All timestamps use [kotlin.time.Instant] from the injected [Clock]. Room stores
 * epoch-milliseconds ([Long]); convert on the boundary via
 * [kotlin.time.Instant.toEpochMilliseconds] and [kotlin.time.Instant.fromEpochMilliseconds].
 * **Do not use [kotlinx.datetime.Instant]** — it is used only for date arithmetic
 * in `todayInSystemZone()`.
 */
class RoomUsageRecorder(private val llmUsageDao: LlmUsageDao, private val clock: Clock) {

    suspend fun record(event: ToolUsageEvent) {
        val entity = LlmUsageEntity(
            id = "${event.profileId}_${event.toolName}_${event.timestamp.epochSeconds}_${java.util.UUID.randomUUID()}",
            profileId = event.profileId,
            toolName = event.toolName,
            modelId = event.modelId,
            inputTokens = event.inputTokens,
            outputTokens = event.outputTokens,
            totalTokens = event.totalTokens,
            costUsdMicros = event.costUsdMicros,
            durationMs = event.durationMs,
            createdAt = event.timestamp.toEpochMilliseconds(),
            error = event.error,
        )
        llmUsageDao.upsert(entity)
    }

    fun observeRecent(profileId: String, limit: Int = 100): Flow<List<ToolUsageEvent>> =
        llmUsageDao.observeRecent(profileId, limit).map { rows ->
            rows.map { it.toEvent() }
        }

    fun observeByDay(profileId: String, days: Int = 30): Flow<List<DailyUsage>> {
        val sinceEpochMs = clock.now().toEpochMilliseconds() - (days.toLong() * TimeConstants.MILLIS_PER_DAY)
        return llmUsageDao.observeByDay(profileId, sinceEpochMs).map { rows ->
            rows.map { it.toDailyUsage() }
        }
    }

    fun observeByTool(profileId: String): Flow<List<ToolUsage>> = llmUsageDao.observeByTool(profileId).map { rows ->
        rows.map { it.toToolUsage() }
    }

    fun observeByModel(profileId: String): Flow<List<ModelUsage>> = llmUsageDao.observeByModel(profileId).map { rows ->
        rows.map { it.toModelUsage() }
    }

    suspend fun prune(olderThanDays: Int = 90) {
        val cutoff = clock.now().toEpochMilliseconds() - (olderThanDays.toLong() * TimeConstants.MILLIS_PER_DAY)
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
