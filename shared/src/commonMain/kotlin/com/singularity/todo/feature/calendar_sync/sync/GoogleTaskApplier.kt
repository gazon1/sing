package com.singularity.todo.feature.calendar_sync.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventDao
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowDao
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec
import com.singularity.todo.feature.calendar_sync.domain.logic.GoogleEventTaskMapper
import com.singularity.todo.feature.calendar_sync.domain.logic.MergeResult
import com.singularity.todo.feature.calendar_sync.domain.logic.toShadow
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Applies what Google said to the tasks and reminders those events map onto.
 *
 * ## Why this is separate from [GoogleSyncEngine]
 *
 * The engine decides *what* should change; this decides how that reaches the app's own
 * models. Keeping them apart is what lets the engine be tested against a fake event source
 * and three in-memory DAOs with no task table in sight, and it puts the mapping — the part
 * with the interesting edge cases — in a pure object that needs no coroutine scope at all.
 *
 * ## What it deliberately does not do
 *
 * It creates no reminders. Importing someone's calendar must not decide, on their behalf,
 * that a dentist appointment now nags them — a reminder is a promise the app makes and the
 * user has to agree to. It does move existing ones when a task is rescheduled, because a
 * reminder that fires at the old time for a task that no longer happens then is simply
 * wrong.
 */
class GoogleTaskApplier(
    private val taskRepository: TaskRepository,
    private val reminderRepository: ReminderRepository,
    /**
     * Re-arms the OS alarm for a reminder whose time moved.
     *
     * Not optional, and the reason is the shape of the bug it prevents. Writing a new
     * `fireAt` to Room is *not* rescheduling: nothing observes that table, and the alarm is
     * armed once when the reminder is created. So a reminder moved by a remote change kept
     * firing at the old time — for a task that no longer happens then — while the new time
     * had no alarm at all. Correct data, no user-visible effect, which is the hardest kind to
     * notice. Both platforms implement the port, so there is no desktop path to stub out.
     */
    private val reminderScheduler: ReminderScheduler,
    private val shadowDao: GoogleEventShadowDao,
    private val importDao: CalendarImportEventDao,
    private val userId: UserId,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val logger: Logger = Logger.withTag("GoogleTaskApplier"),
) {

    /** What one applier pass did, so a caller can report it without re-deriving anything. */
    data class ApplyResult(
        /** Foreign events turned into tasks. */
        val imported: Int = 0,
        /** Tasks whose fields a remote change rewrote. */
        val updated: Int = 0,
        /** Reminders moved because their task moved. */
        val remindersMoved: Int = 0,
        /** Imports that could not become tasks. */
        val failed: Int = 0,
    )

    /**
     * Converts every staged foreign event into a real, editable task.
     *
     * Runs after the page walk rather than per event, so a partially-read page cannot leave
     * tasks behind for events the pass later decided to skip.
     *
     * Failures are counted, not thrown: one event whose title the task validator rejects
     * must not abandon the other four hundred in the same import.
     */
    suspend fun adoptPendingImports(): ApplyResult {
        val pending = importDao.getAll(userId.value).filter { it.taskId == null }
        var imported = 0
        var failed = 0

        for (row in pending) {
            // Counting here and deciding there keeps the loop free of branches: two
            // `continue`s in one body meant the reader had to hold both failure shapes at
            // once to tell whether an event was skipped or half-imported.
            if (importOne(row)) imported++ else failed++
        }
        return ApplyResult(imported = imported, failed = failed)
    }

    /**
     * Imports one staged row, returning whether it became a task.
     *
     * Split out so [adoptPendingImports] reads as a tally over a list rather than as a
     * sequence of guards, and so this one's two failure modes are described in one place.
     */
    private suspend fun importOne(row: CalendarImportEventEntity): Boolean {
        val event = row.toGoogleEvent()
        // A cancelled event, or one the mapper cannot describe, has no task to create — so
        // the null is checked *before* the write, not reported as a failed write.
        val task = GoogleEventTaskMapper.newTask(
            event,
            TaskId(idGenerator.next()),
            userId,
            clock.now(),
            timeZone(),
        )
        if (task == null) {
            logger.w { "Could not import Google event ${row.eventId}: it is not a task-shaped event" }
            return false
        }

        // `create` returns a `Result` of its own, so a successful call can still be a
        // business failure — a validator, a constraint. That is not an exception and is not
        // caught by the wrapper; both outcomes have to be inspected separately, and
        // conflating them would report a constraint violation as a crash.
        val created = runCatchingResult { taskRepository.create(task) }
        val saved = created.getOrNull()?.getOrNull()
        if (saved == null) {
            logger.w(created.exceptionOrNull() ?: created.getOrNull()?.exceptionOrNull()) {
                "Could not import Google event ${row.eventId} as a task"
            }
            return false
        }

        importDao.linkTask(userId.value, row.eventId, saved.id.value)
        // The shadow is what makes this event two-way from now on. Without it the next pass
        // sees an event with no recorded ancestor, every field reads as a conflict, and the
        // event is stuck: imported once, never synced again.
        shadowDao.upsert(
            GoogleEventShadowEntity(
                userId = userId.value,
                eventId = row.eventId,
                taskId = saved.id.value,
                calendarId = row.calendarId,
                etag = row.etag,
                baseJson = EventShadowCodec.encode(event.toShadow()),
                remoteUpdatedAt = null,
                lastSyncedAt = clock.now().toEpochMilliseconds(),
            ),
        )
        return true
    }

    /**
     * What [existing] should become, or null when there is nothing to write.
     *
     * The three ways of getting null — a merge that decided nothing for the task, a task
     * that has since been deleted, and an event that is no longer live — are one way from
     * the caller's side, because each means "no row left to write". Keeping them here is
     * what lets [applyRemoteChange] read as one linear path rather than four early exits.
     *
     * [existing] is passed in rather than fetched, so the row that is tested is the row that
     * is written: reading it twice could compare against one version and update another.
     */
    private fun rewrittenTask(
        existing: Task,
        edits: GoogleEventTaskMapper.TaskEdits,
    ): Task? {
        if (edits.isEmpty) return null
        // `updated == existing` is the "the merge wanted what we already have" case. It is
        // normal — the other side of a two-way sync usually agrees — and writing anyway
        // would bump `updatedAt` on every pass and make a settled task look permanently
        // edited, which is the kind of phantom change that then syncs back out to Google.
        return existing.withEdits(edits).takeIf { it != existing }
    }

    /**
     * The Google event the task behind [taskId] wants to be, or null when there is no such task.
     *
     * The engine calls this to get the *local* side of its 3-way merge. That side has to come
     * from the task. Reading it from the shadow instead — which is what this used to do
     * indirectly — makes "did the task change?" identically false for every field, so
     * [FieldOutcome.Push] can never be produced and nothing is ever written to Google.
     *
     * [remote] is carried through so the result keeps Google's identity (id, etag) and the
     * two fields the app has no model for. Only the app-owned fields come from the task, so a
     * task with no location cannot blank a real one on the user's calendar.
     */
    suspend fun desiredEventFor(taskId: TaskId, remote: GoogleEvent): GoogleEvent? {
        val task = taskRepository.get(taskId) ?: return null
        return remote.copy(
            title = task.title,
            description = task.description,
            startsAt = task.dueDate?.atTime(task.dueTime ?: MIDNIGHT)?.toInstant(timeZone()),
            endsAt = task.endDate?.atTime(task.endTime ?: MIDNIGHT)?.toInstant(timeZone()),
            allDay = task.dueTime == null,
        )
    }

    /**
     * Every task that should have a Google event, as the event it wants.
     *
     * The selection rule deliberately matches the system-calendar worker
     * (`CalendarSyncWorker.kt:71`): neither completed nor trashed. Divergence would mean a
     * task appears on one calendar and not the other, which is worse than either policy
     * being wrong.
     */
    suspend fun desiredEvents(calendarId: String): List<GoogleEvent> =
        taskRepository.observeAll().first()
            .filter { !it.isCompleted && !it.isTrashed }
            .map { it.asDesiredEvent(calendarId) }

    /**
     * [remote] with this task's own fields applied over it.
     *
     * The inverse of what the pull path does, and the reason both exist rather than one
     * shared helper: walking *to* Google must not carry the task's absent location and null
     * recurrence over a real event, and walking *from* Google must not carry Google's values
     * onto the task.
     */
    private fun Task.asDesiredEvent(calendarId: String): GoogleEvent {
        val tz = timeZone()
        return GoogleEvent(
            // A placeholder. Nothing local knows a Google event id: the planner substitutes
            // the stored one for an existing event, and `insert` mints the first real one.
            id = GoogleEventId(PLACEHOLDER_EVENT_ID),
            calendarId = calendarId,
            title = title,
            description = description,
            startsAt = dueDate?.atTime(dueTime ?: MIDNIGHT)?.toInstant(tz),
            endsAt = endDate?.atTime(endTime ?: MIDNIGHT)?.toInstant(tz),
            // "No time" is how this model spells an all-day task, and an all-day task has no
            // moment to be reminded about. Reading it any other way would put every
            // date-only task on the calendar at midnight.
            allDay = dueTime == null,
            // Never from the task. The app does not parse RFC 5545 and the rule has to
            // round-trip byte-identical, so it stays null and the planner keeps Google's copy.
            location = null,
            recurrenceRule = null,
            status = GoogleEventStatus.Confirmed,
            etag = null,
            updatedAt = null,
            taskId = id.value,
        )
    }

    /**
     * Applies the `ApplyToTask` half of [merge] to the task behind [taskId].
     *
     * Reports zero when nothing was written: a merge that decided nothing for the task, a
     * task that has since been deleted, and an event that is no longer live all land here,
     * because in each case there is nothing left to write to.
     */
    suspend fun applyRemoteChange(
        taskId: TaskId,
        merge: MergeResult,
        remote: GoogleEvent,
    ): ApplyResult {
        val target = remoteWriteTarget(taskId, merge, remote) ?: return ApplyResult()
        if (!writeUpdatedTask(taskId, target.updated)) return ApplyResult(failed = 1)

        // Only a move drags the reminders. Rescheduling on a rename would shift a
        // notification by however many characters the new title happens to have.
        val moved = if (target.edits.movesTime) moveReminders(target.existing, target.updated) else 0
        return ApplyResult(updated = 1, remindersMoved = moved)
    }

    /**
     * The task to write for a remote change, or null when there is nothing to write.
     *
     * Null covers all three of the merge's "nothing happened" cases at once — a dead event,
     * a task that has since been deleted, and an edit that would be a no-op — because in
     * each of them there is no second task to write.
     */
    private suspend fun remoteWriteTarget(
        taskId: TaskId,
        merge: MergeResult,
        remote: GoogleEvent,
    ): RemoteWriteTarget? {
        if (!remote.isLive) return null
        val edits = GoogleEventTaskMapper.editsFor(merge, remote, timeZone())
        val existing = taskRepository.get(taskId) ?: return null
        val updated = rewrittenTask(existing, edits) ?: return null
        return RemoteWriteTarget(existing = existing, updated = updated, edits = edits)
    }

    /**
     * Writes [updated], reporting whether both layers of the call agreed that it landed.
     */
    private suspend fun writeUpdatedTask(taskId: TaskId, updated: Task): Boolean {
        // `update` returns a `Result` of its own, so the wrapper only reports whether the
        // call *threw*. Checking the outer layer alone would read every business rejection
        // — a validator, a constraint — as a successful write, and then move the reminders
        // for a task that was never updated. Both layers are inspected for that reason.
        val written = runCatchingResult { taskRepository.update(updated) }
        val failed = written.isFailure || written.getOrNull()?.isFailure == true
        if (failed) {
            logger.w(written.exceptionOrNull() ?: written.getOrNull()?.exceptionOrNull()) {
                "Could not apply Google change to task $taskId"
            }
        }
        return !failed
    }

    /** The pair of task versions one remote change rewrites, plus the edits that did it. */
    private data class RemoteWriteTarget(
        val existing: Task,
        val updated: Task,
        val edits: GoogleEventTaskMapper.TaskEdits,
    )

    /**
     * Moves a task's one-shot reminders to follow its new due time.
     *
     * The offset is preserved rather than recomputed: the user chose "10 minutes before",
     * and a task that moved from Tuesday to Thursday should still be "10 minutes before".
     *
     * Recurring reminders are left alone. Their `fireAt` is the next occurrence of a
     * pattern the app recomputes, not a single point in time, and rewriting it from a new
     * due date would corrupt the series rather than move it.
     *
     * A reminder that lands in the past is still written. The alternative — dropping it —
     * would delete something the user asked for on the strength of a scheduling detail they
     * never saw, and the notification layer already has a defined behaviour for a
     * past-due reminder.
     */
    private suspend fun moveReminders(before: Task, after: Task): Int {
        val tz = timeZone()
        val oldDue = before.dueDate?.atTime(before.dueTime ?: MIDNIGHT)?.toInstant(tz)?.toEpochMilliseconds()
        val newDue = after.dueDate?.atTime(after.dueTime ?: MIDNIGHT)?.toInstant(tz)?.toEpochMilliseconds()
        if (oldDue == null || newDue == null || oldDue == newDue) return 0

        val deltaMs = newDue - oldDue
        val taskId = before.id
        var moved = 0
        reminderRepository.watchByTask(taskId).first()
            .filter { it.recurringPattern == null }
            .forEach { reminder ->
                val shifted = reminder.copy(fireAt = reminder.fireAt + deltaMs)
                val result = runCatchingResult { reminderRepository.upsert(shifted) }
                if (result.isSuccess) {
                    // The row write above is not the reschedule. The alarm is armed once, at
                    // creation, and nothing observes the table; without this the notification
                    // fires at the *old* time and the new time is never armed at all.
                    // Cancel first because Android's alarm is keyed by reminder id, and
                    // re-scheduling without cancelling leaves the old one in place.
                    val rescheduled = runCatchingResult {
                        reminderScheduler.cancel(shifted.id, userId)
                        reminderScheduler.schedule(shifted)
                    }
                    if (rescheduled.isFailure) {
                        // Counted, not thrown: a failed alarm leaves the notification late,
                        // which is recoverable, whereas throwing here would abandon the rest
                        // of the import and the task edits already applied.
                        logger.w(rescheduled.exceptionOrNull()) {
                            "Moved reminder ${shifted.id.value} in the database but could not re-arm its alarm"
                        }
                    }
                    moved++
                } else {
                    logger.w(result.exceptionOrNull()) {
                        "Could not move reminder ${reminder.id.value} with task $taskId"
                    }
                }
            }
        return moved
    }

    private fun Task.withEdits(edits: GoogleEventTaskMapper.TaskEdits): Task = copy(
        // A remotely-cleared title still has to leave the task with a name; `Task.title`
        // is not nullable, so the same placeholder the mapper uses applies here.
        title = if (GoogleEventTaskMapper.TaskField.Title in edits.changed) {
            edits.title ?: GoogleEventTaskMapper.UNTITLED
        } else {
            title
        },
        description = if (GoogleEventTaskMapper.TaskField.Description in edits.changed) {
            edits.description
        } else {
            description
        },
        dueDate = if (GoogleEventTaskMapper.TaskField.DueDate in edits.changed) edits.dueDate else dueDate,
        dueTime = if (GoogleEventTaskMapper.TaskField.DueTime in edits.changed) edits.dueTime else dueTime,
        endDate = if (GoogleEventTaskMapper.TaskField.EndDate in edits.changed) edits.endDate else endDate,
        endTime = if (GoogleEventTaskMapper.TaskField.EndTime in edits.changed) edits.endTime else endTime,
        updatedAt = clock.now(),
    )

    /**
     * Rebuilds the event a staged import row came from.
     *
     * The row is a projection of the event, so this is a lossy round trip — the fields the
     * table does not carry come back absent. That is sound precisely because the row was
     * written from an event the mapper can describe, and because the shadow written
     * alongside it records the same values: the merge's ancestor is this shape, not the
     * full original.
     */
    private fun CalendarImportEventEntity.toGoogleEvent() = GoogleEvent(
        id = GoogleEventId(eventId),
        calendarId = calendarId,
        title = title,
        description = description,
        startsAt = startsAt?.let { Instant.fromEpochMilliseconds(it) },
        endsAt = endsAt?.let { Instant.fromEpochMilliseconds(it) },
        allDay = allDay,
        location = location,
        recurrenceRule = recurrenceRule,
        status = GoogleEventStatus.Confirmed,
        etag = etag,
        updatedAt = null,
        taskId = null,
    )

    private companion object {
        val MIDNIGHT = LocalTime(0, 0)

        /**
         * Stands in for "no Google event yet" in a desired-event built from a task.
         *
         * A constant rather than a fresh random id on every call: the planner matches tasks to
         * events by `taskId`, never by event id, so this value only has to be *stable and
         * obviously-not-real*. Making it random would suggest it means something.
         */
        const val PLACEHOLDER_EVENT_ID = "__none__"
    }
}
