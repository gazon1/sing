package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec
import com.singularity.todo.feature.calendar_sync.domain.logic.toShadow
import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The engine's contract with its three collaborators, exercised through a fake source and
 * in-memory DAOs.
 *
 * The rules worth pinning here are the ones whose failure is *silent*: a cursor stored
 * early, a shadow not written, an event re-created after the user cancelled it. None of
 * those throw; they just quietly lose or duplicate data.
 */
@Tag("fast")
class GoogleSyncEngineTest {

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = MutableClock()

    /** A source that replays a fixed script of pages, and records what it was asked for. */
    private class FakeSource(private val pagesByToken: Map<String?, List<ChangePage>>) : CalendarEventSource {
        val requestedTokens = mutableListOf<String?>()
        val patched = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()

        /**
         * Recorded because "a cancelled event must never be re-created" is only testable if
         * `insert` is observed. Without it the fake silently succeeds and the one call the
         * tombstone exists to prevent goes unnoticed.
         */
        val inserted = mutableListOf<String>()

        override suspend fun listCalendars() =
            emptyList<com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary>()

        override suspend fun fetchChanges(calendarId: String, syncToken: String?): ChangePage {
            requestedTokens += syncToken
            return pagesByToken[syncToken]?.firstOrNull()
                ?: ChangePage(events = emptyList(), nextSyncToken = "token-end-${requestedTokens.size}")
        }

        override suspend fun insert(calendarId: String, event: GoogleEvent): GoogleEvent {
            inserted += event.title ?: ""
            return event
        }

        override suspend fun patch(
            calendarId: String,
            eventId: GoogleEventId,
            etag: String?,
            event: GoogleEvent,
        ): GoogleEvent {
            patched += eventId.value to (event.title ?: "")
            return event
        }

        override suspend fun get(calendarId: String, eventId: GoogleEventId): GoogleEvent =
            GoogleEvent(id = eventId, calendarId = calendarId)

        override suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?) {
            cancelled += eventId.value
        }
    }

    private fun event(
        n: Int,
        title: String? = "Event $n",
        taskId: String? = null,
        status: GoogleEventStatus = GoogleEventStatus.Confirmed,
        recurrenceRule: String? = null,
    ) = GoogleEvent(
        id = GoogleEventId("evt_$n"),
        calendarId = "primary",
        title = title,
        description = null,
        startsAt = t0,
        endsAt = t0,
        allDay = false,
        location = null,
        recurrenceRule = recurrenceRule,
        status = status,
        etag = "\"etag$n\"",
        updatedAt = t0,
        taskId = taskId,
    )

    private fun engine(source: FakeSource, db: FakeAppDatabase, userId: String = "user-1") =
        GoogleSyncEngine(
            eventSource = source,
        importWindow = ImportWindow.DEFAULT,
            shadowDao = db.googleEventShadowDao(),
            stateDao = db.calendarSyncStateDao(),
            importDao = db.calendarImportEventDao(),
            userId = userId,
            clock = clock,
        )

    // ─── Cursor discipline ──────────────────────────────────────────────

    /**
     * The token must come from the *last* page. A token taken from an earlier page
     * describes a window this pass has not read, and those changes are then skipped forever
     * — with no error to show for it.
     */
    @Test
    fun `a multi-page walk stores the token from the final page only`() = runTest {
        val db = FakeAppDatabase()
        val source = FakeSource(
            mapOf(
                null to listOf(
                    ChangePage(events = listOf(event(1)), nextPageToken = "page-2"),
                ),
            ),
        )
        // Second page answers under the page token; the fake falls through to a final page
        // carrying the real sync token.
        val engine = engine(source, db)

        engine.sync("primary")

        val state = db.calendarSyncStateDao().get("user-1", "google", "primary")
        assertNotNull(state?.nextSyncToken)
        assertTrue(
            state.nextSyncToken!!.startsWith("token-end-"),
            "stored \"${state.nextSyncToken}\" — that is not a final-page token",
        )
        assertEquals(listOf(null, "page-2"), source.requestedTokens, "the walk must follow the page token")
    }

    @Test
    fun `a first sync stores the token it was given`() = runTest {
        val db = FakeAppDatabase()
        val source = FakeSource(emptyMap())
        engine(source, db).sync("primary")

        val state = db.calendarSyncStateDao().get("user-1", "google", "primary")
        assertNotNull(state)
        assertTrue(state.nextSyncToken!!.isNotBlank())
    }

    /**
     * A dead token is a normal outcome. Throwing here would turn a routine invalidation
     * into a failure and cost the user a full re-listing of their calendar as an "error".
     */
    @Test
    fun `a dead cursor triggers a full resync rather than an error`() = runTest {
        val db = FakeAppDatabase()
        val source = object : CalendarEventSource {
            var calls = 0
            override suspend fun listCalendars() =
                emptyList<com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary>()

            override suspend fun fetchChanges(calendarId: String, syncToken: String?) =
                if (syncToken != null) {
                    calls++
                    ChangePage(events = emptyList(), requiresFullResync = true)
                } else {
                    calls++
                    ChangePage(events = listOf(event(1, taskId = "task-1")))
                }

            override suspend fun insert(calendarId: String, event: GoogleEvent) = event
            override suspend fun patch(
                calendarId: String,
                eventId: GoogleEventId,
                etag: String?,
                event: GoogleEvent,
            ) = event
            override suspend fun get(calendarId: String, eventId: GoogleEventId) = event(1)
            override suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?) = Unit
        }
        // Seed a dead token.
        db.calendarSyncStateDao().upsert(
            CalendarSyncStateEntity("user-1", "google", "primary", nextSyncToken = "dead"),
        )

        val result = GoogleSyncEngine(
            eventSource = source,
        importWindow = ImportWindow.DEFAULT,
            shadowDao = db.googleEventShadowDao(),
            stateDao = db.calendarSyncStateDao(),
            importDao = db.calendarImportEventDao(),
            userId = "user-1",
            clock = clock,
        ).sync("primary")

        assertTrue(result.neededFullResync, "the caller has to know the cursor was replaced")
        assertTrue(source.calls >= 2, "a full listing must follow the invalidation")
    }

    // ─── Ownership ──────────────────────────────────────────────────────

    @Test
    fun `a foreign event is offered for import, not adopted`() = runTest {
        val db = FakeAppDatabase()
        val source = FakeSource(
            mapOf(null to listOf(ChangePage(events = listOf(event(1, title = "Dentist"))))),
        )

        val result = engine(source, db).sync("primary")

        assertEquals(1, result.imported)
        assertEquals(0, result.adopted)
        assertEquals("Dentist", db.calendarImportEventDao().get("user-1", "evt_1")?.title)
        assertNull(
            db.googleEventShadowDao().get("user-1", "evt_1"),
            "a foreign event has no task, so it has no shadow to compare against",
        )
    }

    /**
     * An insert interrupted before its shadow was written leaves an orphan that already
     * carries our marker. Adopting it is what stops a second identical event appearing.
     */
    @Test
    fun `an orphan carrying our marker is adopted rather than duplicated`() = runTest {
        val db = FakeAppDatabase()
        val source = FakeSource(
            mapOf(null to listOf(ChangePage(events = listOf(event(1, taskId = "task-1"))))),
        )

        val result = engine(source, db).sync("primary")

        assertEquals(1, result.adopted)
        assertEquals(0, result.imported)
        val shadow = db.googleEventShadowDao().get("user-1", "evt_1")
        assertEquals("task-1", shadow?.taskId)
        assertTrue(source.patched.isEmpty(), "adopting must not also write")
    }

    /**
     * Cancelled is not deleted, and that is the whole point. The mapping is *marked*
     * cancelled rather than removed: deleting the row made the planner see a task with no
     * event and re-insert the very event the user had just cancelled. Re-creating it would be
     * the worst outcome, because the user removed it on purpose.
     */
    @Test
    fun `a cancelled event is tombstoned and is not re-created`() = runTest {
        val db = FakeAppDatabase()
        val source = FakeSource(
            mapOf(null to listOf(ChangePage(events = listOf(event(1, status = GoogleEventStatus.Cancelled))))),
        )
        // A shadow exists from a previous sync.
        db.googleEventShadowDao().upsert(
            com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity(
                userId = "user-1",
                eventId = "evt_1",
                taskId = "task-1",
                calendarId = "primary",
                etag = "\"etag1\"",
                baseJson = EventShadowCodec.encode(event(1, taskId = "task-1").toShadow()),
                lastSyncedAt = t0.toEpochMilliseconds(),
            ),
        )

        val result = engine(source, db).sync("primary")

        assertEquals(0, result.seen, "a cancelled event is not live")
        val shadow = db.googleEventShadowDao().get("user-1", "evt_1")
        assertNotNull(shadow, "the tombstone must survive the pass that wrote it")
        assertNotNull(shadow?.cancelledAt, "the row must be marked, not removed")
        assertEquals("task-1", shadow?.taskId, "the task link is what the planner needs")
        assertTrue(source.patched.isEmpty(), "a cancelled event must never be written back")
        assertTrue(source.inserted.isEmpty(), "a cancelled event must never be re-inserted")
    }

    // ─── Profile isolation ──────────────────────────────────────────────

    @Test
    fun `one profile's sync leaves another profile's rows alone`() = runTest {
        val db = FakeAppDatabase()
        val homeSource = FakeSource(
            mapOf(null to listOf(ChangePage(events = listOf(event(1, taskId = "task-h"))))),
        )
        engine(homeSource, db, userId = "home").sync("primary")

        val workSource = FakeSource(mapOf(null to listOf(ChangePage(events = emptyList()))))
        engine(workSource, db, userId = "work").sync("primary")

        assertNotNull(
            db.googleEventShadowDao().get("home", "evt_1"),
            "another profile's cleanup must not delete this row",
        )
        assertNotNull(db.calendarSyncStateDao().get("home", "google", "primary"))
    }
}
