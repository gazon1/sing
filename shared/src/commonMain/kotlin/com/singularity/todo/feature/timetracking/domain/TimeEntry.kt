package com.singularity.todo.feature.timetracking.domain

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.time.Instant

/**
 * The kind of time entry — determines whether it counts toward progress.
 *
 * - [Work] counts toward the task's total tracked time and progress fraction.
 * - [Recording] is audio/video and is explicitly excluded from progress (Lotti rule).
 */
enum class TimeEntryKind {
    /** Counts toward task progress. */
    Work,

    /** Audio/video — does not count as work. */
    Recording,
}

/**
 * How the time entry was created.
 *
 * - [Timer] — started/stopped by the live timer on the task hub.
 * - [Manual] — user entered start+end times by hand.
 * - [Pomodoro] — completed Pomodoro work phase (break phases are not recorded).
 * - [AiProposal] — AI suggested and user confirmed a time entry.
 */
enum class TimeEntrySource {
    Timer,
    Manual,
    Pomodoro,
    AiProposal,
}

/**
 * A single time tracking entry.
 *
 * @param id Unique identity.
 * @param taskId The task this entry is tracking time against.
 * @param userId The user who owns this entry.
 * @param startedAt When the session started (epoch millis).
 * @param endedAt When the session ended. Null means the entry is currently running.
 * @param kind Work or Recording — determines whether it counts toward progress.
 * @param source How the entry was created.
 * @param note Optional free-text note.
 * @param createdAt Creation timestamp.
 * @param updatedAt Last modification timestamp.
 * @param deletedAt Soft-delete timestamp (null = not deleted).
 * @param serverVersion Sync server version.
 * @param hlc Hybrid Logical Clock timestamp.
 */
@Serializable
data class TimeEntry(
    val id: TimeEntryId,
    val taskId: TaskId,
    val userId: UserId,
    val startedAt: Instant,
    val endedAt: Instant?,
    val kind: TimeEntryKind,
    val source: TimeEntrySource,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity {
    /** True if the entry is still in progress (no end time set). */
    val isRunning: Boolean get() = endedAt == null

    /** Duration in milliseconds. Returns null if [isRunning]. */
    val durationMs: Long? get() = endedAt?.let { (it - startedAt).inWholeMilliseconds }

    // SyncableEntity
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.TimeEntry
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc

    override fun toJson(): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ser = serializer<TimeEntry>()
        return StableJson.encodeToJsonElement(ser, this) as JsonObject
    }
}
