package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventDao
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateEntity
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowDao
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.BidirectionalMerge
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadow
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowRef
import com.singularity.todo.feature.calendar_sync.domain.logic.GooglePushAction
import com.singularity.todo.feature.calendar_sync.domain.logic.GooglePushPlanner
import com.singularity.todo.feature.calendar_sync.domain.logic.toShadow
import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Reconciles one Google calendar with the local tasks.
 *
 * ## Pull before push
 *
 * Pushing first would write our idea of an event to Google before we had heard about the
 * other device's edit to it, and that edit would be lost. Pulling first means every write
 * is made against a state we have just read.
 *
 * ## The cursor moves last
 *
 * An incremental token summarises a window of changes. If the process dies part-way through
 * the pages, the cursor must not have moved: re-running is cheap, advancing past an unread
 * page skips those changes permanently. So the token is stored once, after the entire walk.
 *
 * ## One mutex, because the pull writes what a later pass reads
 *
 * Two passes sharing the shadow rows would interleave: one reading a row the other is
 * rewriting, and both concluding the event changed when it had not.
 *
 * ## What this class does *not* do
 *
 * It does not touch tasks. Turning a remote title into a task title, or rescheduling a
 * reminder, is the task layer's business. This one records what Google said and what it was
 * told, which is what keeps it testable with a fake event source and in-memory DAOs — and
 * why the task applier is separate work rather than more of this.
 */
class GoogleSyncEngine(
    private val eventSource: CalendarEventSource,
    private val shadowDao: GoogleEventShadowDao,
    private val stateDao: CalendarSyncStateDao,
    private val importDao: CalendarImportEventDao,
    private val userId: String,
    private val clock: Clock,
    private val provider: String = PROVIDER_GOOGLE,
    /**
     * No default, so there is one place the window is chosen.
     *
     * It used to be `= ImportWindow.DEFAULT`, and the event source had the same default,
     * and the settings screen read the constant a third time. Three sites, no compiler
     * error if one changed. Bound in `calendarSyncModule()` and injected everywhere.
     */
    private val importWindow: ImportWindow,
    /**
     * Whether events this app did not create are pulled in as tasks.
     *
     * Separate from the engine's existence because the two directions are separable: a user
     * may want their own tasks on their Google calendar — the projection they had before
     * this feature existed — without wanting the rest of their life imported alongside. The
     * flag gates only the import branch; the push of the app's own events is untouched, so
     * turning it off never silently stops a sync the user was relying on.
     *
     * Read at pass time rather than captured, so flipping the switch takes effect on the
     * next run without the engine being rebuilt. Suspending because the setting lives in a
     * flow-backed store: capturing the value at construction would freeze the toggle at
     * whatever it was when the engine was built, which for a per-profile factory is very
     * likely to be the wrong profile's answer.
     */
    private val importForeignEvents: suspend () -> Boolean = { true },
    /**
     * Applies the merge's task-side decisions to tasks and reminders.
     *
     * Injected rather than constructed so this class stays testable with no task table in
     * sight, and so the split is explicit: this engine decides *what* changed, the applier
     * decides how that reaches the app's own models. A null applier is legal and means
     * "record only" — the engine then computes every merge decision and applies none of it,
     * which is useful in tests and honest about the fact that nothing else is doing it.
     */
    private val applier: GoogleTaskApplier? = null,
) : GoogleSyncPass {

    private val mutex = Mutex()

    /** What one pass did, so a caller can report it without re-deriving anything. */
    data class PassResult(
        /** Events read from Google, excluding cancellations. */
        val seen: Int = 0,
        /** Events this app wrote to Google. */
        val pushed: Int = 0,
        /** Foreign events offered to the user. */
        val imported: Int = 0,
        /** Events adopted after an interrupted write left no shadow. */
        val adopted: Int = 0,
        /** Fields where both sides changed the same value and the write was held back. */
        val heldBack: Int = 0,
        /** A dead cursor forced a full re-listing. A normal outcome, not a failure. */
        val neededFullResync: Boolean = false,
        /** Tasks the applier created from foreign events. */
        val tasksCreated: Int = 0,
        /** Tasks the applier rewrote because Google moved a field. */
        val tasksUpdated: Int = 0,
        /** Reminders moved because their task moved. */
        val remindersMoved: Int = 0,
        /** Writes that failed, whether to Google or into the app. */
        val failed: Int = 0,
    )

    /** Tally for one pass. Private because it never escapes. */
    private class Counts {
        var pushed = 0
        var imported = 0
        var adopted = 0
        var heldBack = 0
        var failed = 0
    }

    /**
     * Runs one pass against [calendarId].
     *
     * One pass, both directions: Google is read first, and any write is made against what
     * was just read. There is deliberately no separate push pass — it would have to
     * re-derive which fields the task layer changed, duplicating the merge, and it would
     * write before it knew about the other device's edit.
     *
     * Every pass is serialised, so a second caller waits rather than interleaving.
     *
     * The mutex is what makes overlapping callers safe, not a re-check.
     */
    override suspend fun sync(calendarId: String): PassResult = mutex.withLock { pull(calendarId) }

    private suspend fun pull(calendarId: String): PassResult {
        val state = stateDao.get(userId, provider, calendarId)
        var firstPage = eventSource.fetchChanges(calendarId, state?.nextSyncToken)
        var neededFullResync = false

        if (firstPage.requiresFullResync) {
            // A dead token is a normal outcome, not a failure. Drop it and read everything.
            stateDao.invalidateToken(userId, provider, calendarId)
            firstPage = eventSource.fetchChanges(calendarId, null)
            neededFullResync = true
        }

        val counts = Counts()
        val liveEventIds = mutableListOf<String>()

        // Event ids whose shadow must survive this pass even though they are not live.
        // A tombstone written by `applyEvent` in this same pass would otherwise be removed
        // moments later by the sweep below, and the tombstone's entire purpose is to outlive
        // the pass that created it.
        val tombstoneIds = mutableListOf<String>()
        var finalSyncToken: String? = null

        // Walk every page, and take the token only from the last one. Google sends
        // `nextSyncToken` with the final page alone; a token from any other page describes a
        // window this pass has not read, and those changes would then be skipped forever.
        var page: ChangePage? = firstPage
        while (page != null) {
            page.events.forEach { event ->
                applyEvent(event, calendarId, liveEventIds, tombstoneIds, counts)
            }
            finalSyncToken = if (page.hasMorePages) null else page.nextSyncToken
            page = if (page.hasMorePages) {
                eventSource.fetchChanges(calendarId, page.nextPageToken)
            } else {
                null
            }
        }

        val now = clock.now().toEpochMilliseconds()
        stateDao.upsert(
            (state ?: newState(calendarId)).copy(
                nextSyncToken = finalSyncToken,
                lastFullSyncAt = if (neededFullResync) now else state?.lastFullSyncAt,
                lastPullAt = now,
            ),
        )

        val retainedEventIds = liveEventIds + tombstoneIds
        if (retainedEventIds.isNotEmpty()) shadowDao.deleteNotIn(userId, retainedEventIds)
        // Rows age out rather than accumulating: an import the user did not ask for, kept
        // forever, is a list that gets steadily harder to trust.
        importDao.deleteOlderThan(userId, importWindow.startInclusive(clock.now()).toEpochMilliseconds())

        // After the walk, never during it: an event staged on page 1 is only converted once
        // the pass knows it read every page, so a pass that dies half-way does not leave
        // tasks behind for events it never finished reconciling.
        val applied = applier?.adoptPendingImports() ?: GoogleTaskApplier.ApplyResult()

        // The other direction, over the local side.
        //
        // The pull walk above is structurally incapable of noticing a task that has no Google
        // event *at all* — such a task never appears in Google's listing, so nothing in the
        // pages above can mention it. Without this walk, creating a task would never put it on
        // the calendar, which is the one thing the system-calendar path has always done.
        val pushedNew = pushLocalTasks(calendarId)

        return PassResult(
            seen = liveEventIds.size,
            pushed = counts.pushed + pushedNew.written,
            imported = counts.imported + applied.imported,
            adopted = counts.adopted,
            heldBack = counts.heldBack,
            neededFullResync = neededFullResync,
            tasksCreated = applied.imported,
            tasksUpdated = applied.updated,
            remindersMoved = applied.remindersMoved,
            failed = counts.failed + applied.failed + pushedNew.failed,
        )
    }

    /** What a local-side push pass managed. */
    private data class PushOutcome(val written: Int = 0, val failed: Int = 0)

    /**
     * Writes Google events for tasks that do not have one yet.
     *
     * Existing events are *not* handled here — those were reconciled field-by-field during the
     * pull walk, where the live `etag` and the merge are both already in hand. This is
     * strictly the create case, which is the one the pull walk cannot see.
     */
    private suspend fun pushLocalTasks(calendarId: String): PushOutcome {
        val applier = applier ?: return PushOutcome()
        val desired = applier.desiredEvents(calendarId)
        if (desired.isEmpty()) return PushOutcome()

        // Mapped to the domain read model here, at the boundary: the planner is domain logic
        // and must not know what a Room row is. `ArchitectureTest` enforces the direction.
        val plan = GooglePushPlanner.plan(
            desired,
            shadowDao.getAll(userId).map {
                EventShadowRef(
                    eventId = it.eventId,
                    calendarId = it.calendarId,
                    taskId = it.taskId,
                    etag = it.etag,
                    base = EventShadowCodec.decodeOrEmpty(it.baseJson),
                )
            },
        )
        var written = 0
        var failed = 0

        // A patch belongs to the pull walk, which already settled it against a live etag.
        // Re-issuing it here would race the merge it just resolved, so it is filtered out
        // rather than skipped inside the loop — one exit per loop, not two.
        for (action in plan.filterIsInstance<GooglePushAction.Insert>()) {
            // The stored form is authoritative, not what we asked for: Google may normalise a
            // title or a time zone, and writing back what we *sent* would make every later
            // merge see a difference that does not exist.
            val stored = runCatchingCancellable { eventSource.insert(calendarId, action.event) }
                .getOrNull()
            if (stored == null) {
                failed++
            } else {
                writeShadow(calendarId, stored, taskId = action.taskId.value)
                written++
            }
        }
        return PushOutcome(written, failed)
    }

    private suspend fun applyEvent(
        event: GoogleEvent,
        calendarId: String,
        liveEventIds: MutableList<String>,
        tombstoneIds: MutableList<String>,
        counts: Counts,
    ) {
        if (!event.isLive) {
            // Cancelled, not deleted. The task survives, and the mapping is kept as a
            // tombstone rather than deleted: the push planner reads the shadow set to decide
            // what still needs creating, so "cancelled" and "never synced" must not look the
            // same. Deleting the row here made the user's cancellation re-create the event on
            // the next pass — the comment below used to say so, and the code did the opposite.
            tombstone(event, tombstoneIds)
            return
        }
        liveEventIds += event.id.value

        val existing = shadowDao.get(userId, event.id.value)
        if (existing == null) {
            if (event.taskId != null) {
                // Ours, but the shadow is missing: an insert was interrupted before its row
                // was written. Adopting the orphan is what stops a second identical event
                // appearing on the next pass.
                writeShadow(calendarId, event, taskId = event.taskId)
                counts.adopted++
            } else if (importForeignEvents()) {
                importDao.upsert(event.toImportRow())
                counts.imported++
            }
            return
        }

        val base = EventShadowCodec.decodeOrEmpty(existing.baseJson)
        // The local side of the merge has to come from the *task*, not from the shadow.
        //
        // An earlier version passed `event.withFieldsFrom(base)` here, which makes `ours`
        // identical to `base` field for field — so `localChanged` in BidirectionalMerge is
        // permanently false, `FieldOutcome.Push` can never be produced, and the branch below
        // that calls `patch` was unreachable. The result was a feature that was called
        // two-way and could only pull: renaming a task never reached the calendar, and no
        // test caught it because the two tests that touch the patch list assert it stays
        // empty. The applier owns tasks, so the engine asks it rather than reading them.
        val desired = existing.taskId
            ?.let { applier?.desiredEventFor(TaskId(it), event) }
            ?: event.withFieldsFrom(base)
        val merge = BidirectionalMerge.merge(
            ours = desired,
            theirs = event,
            base = base,
        )

        if (merge.requiresAttention) {
            // Both sides changed the repeat rule. The write is held: a local series
            // regeneration changes how many occurrences exist, and that is not ours to
            // decide silently. The disagreement persists until a human resolves it.
            counts.heldBack++
        } else {
            if (merge.pushes.isNotEmpty()) {
                // Only the app-owned fields come from the task. Location and the RRULE stay
                // Google's, because the app has no model for the first and deliberately does
                // not parse the second — sending either would blank a real location and
                // rewrite a series on the user's calendar.
                eventSource.patch(calendarId, event.id, existing.etag, desiredOverRemote(desired, event))
                counts.pushed++
            }
            // The other half of the same merge: fields only Google moved are written *down*
            // to the task. Skipping this is why a title typed on a phone would never reach
            // the app — the engine would record the change and no one would apply it.
            val taskId = existing.taskId
            if (taskId != null && merge.applies.isNotEmpty()) {
                applier?.applyRemoteChange(TaskId(taskId), merge, event)
            }
        }

        // The shadow becomes what Google now holds, so the next pass compares against the
        // truth rather than re-deciding the same fields against a stale ancestor.
        shadowDao.upsert(
            existing.copy(
                etag = event.etag,
                baseJson = EventShadowCodec.encode(event.toShadow()),
                remoteUpdatedAt = event.updatedAt?.toEpochMilliseconds(),
                lastSyncedAt = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    /**
     * The event as the app wants it, when no task could be read for it.
     *
     * Only reached when there is no task to ask — a shadow with a null `task_id`, or no
     * applier wired in. Falling back to the last-agreed shadow means "we have no opinion",
     * which the merge then reads as "nothing changed locally" and reports as `NoOp`. That is
     * the right default for a pull-only pass, and it is why this must never be used as the
     * `ours` side when a task *does* exist: doing so silently disables pushing, which is
     * exactly the bug this function's callers used to have.
     */
    private fun GoogleEvent.withFieldsFrom(base: EventShadow): GoogleEvent = copy(
        title = base.title,
        description = base.description,
        startsAt = base.startsAt,
        endsAt = base.endsAt,
        allDay = base.allDay,
        location = base.location,
        recurrenceRule = base.recurrenceRule,
    )

    /**
     * The patch payload: Google's event with the app-owned fields replaced by the task's.
     *
     * The inverse of [withFieldsFrom]. Google's `patch` overwrites what it is sent and
     * leaves the rest alone, so carrying `remote` as the base and overwriting only the five
     * fields the app models is what keeps `location` and `recurrenceRule` intact on the
     * user's real calendar entry.
     */
    private fun desiredOverRemote(desired: GoogleEvent, remote: GoogleEvent): GoogleEvent = remote.copy(
        title = desired.title,
        description = desired.description,
        startsAt = desired.startsAt,
        endsAt = desired.endsAt,
        allDay = desired.allDay,
    )

    private suspend fun writeShadow(calendarId: String, event: GoogleEvent, taskId: String?) {
        shadowDao.upsert(
            GoogleEventShadowEntity(
                userId = userId,
                eventId = event.id.value,
                taskId = taskId,
                calendarId = calendarId,
                etag = event.etag,
                baseJson = EventShadowCodec.encode(event.toShadow()),
                remoteUpdatedAt = event.updatedAt?.toEpochMilliseconds(),
                lastSyncedAt = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    /**
     * Records [event] as cancelled, keeping whatever the row already knew.
     *
     * The task survives a cancellation — the user cancelled an *event*, and silently deleting
     * their task would be a far more surprising outcome than the one they asked for — so the
     * row's `taskId` and `baseJson` are preserved rather than reset. That matters for a
     * second reason: if the user later un-cancels, or re-creates the event themselves, the
     * ancestor is still the one both sides last agreed on, and the merge has a baseline to
     * reason from instead of treating it as a first meeting.
     *
     * [tombstoneIds] records that this event id must survive the sweep at the end of the
     * pass. Without it the tombstone is created and deleted within the same pass, which is
     * the bug this method exists to remove.
     */
    private suspend fun tombstone(event: GoogleEvent, tombstoneIds: MutableList<String>) {
        tombstoneIds += event.id.value
        val previous = shadowDao.get(userId, event.id.value)
        shadowDao.upsert(
            GoogleEventShadowEntity(
                userId = userId,
                eventId = event.id.value,
                taskId = previous?.taskId,
                calendarId = previous?.calendarId ?: event.calendarId,
                etag = previous?.etag,
                baseJson = previous?.baseJson
                    ?: EventShadowCodec.encode(event.toShadow()),
                remoteUpdatedAt = clock.now().toEpochMilliseconds(),
                cancelledAt = clock.now().toEpochMilliseconds(),
                lastSyncedAt = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun GoogleEvent.toImportRow() = CalendarImportEventEntity(
        userId = userId,
        eventId = id.value,
        calendarId = calendarId,
        title = title,
        description = description,
        location = location,
        startsAt = startsAt?.toEpochMilliseconds(),
        endsAt = endsAt?.toEpochMilliseconds(),
        allDay = allDay == true,
        recurrenceRule = recurrenceRule,
        etag = etag,
        lastSyncedAt = clock.now().toEpochMilliseconds(),
        importedAt = clock.now().toEpochMilliseconds(),
    )

    private fun newState(calendarId: String) =
        CalendarSyncStateEntity(userId = userId, provider = provider, calendarId = calendarId)

    companion object {
        const val PROVIDER_GOOGLE = "google"
    }
}
