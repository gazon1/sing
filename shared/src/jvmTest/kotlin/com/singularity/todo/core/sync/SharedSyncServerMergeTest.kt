@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import com.singularity.todo.test.fakes.SharedSyncServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The merge the real server performs, proved on the fixture that stands in for
 * it.
 *
 * The rules are the ones in `supabase/migrations/2026-10-07-sync_schema.sql`:
 * a field is written when the patch's clock beats the clock stored for that
 * field, the row's version moves only when something changed, and the pull
 * event carries the merged row rather than the operations that changed it.
 */
@Tag("fast")
class SharedSyncServerMergeTest {
    @Test
    fun `two devices editing different fields keep both edits`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)
            val b = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            push(b, patch("p2", Hlc.of(2_000L, 0, "dev-b"), set("description", "From the corner shop")))

            val events = a.getEventsSince(0L)
            assertEquals(2, events.size)
            val merged = events.last().mergedDocument()
            assertEquals(JsonPrimitive("Buy milk"), merged["title"])
            assertEquals(JsonPrimitive("From the corner shop"), merged["description"])
        }

    @Test
    fun `the older clock loses the field and the answer says so`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)
            val b = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(2_000L, 0, "dev-a"), set("title", "Newer")))
            val answer = push(b, patch("p2", Hlc.of(1_000L, 0, "dev-b"), set("title", "Older")))

            assertTrue(answer.ok)
            assertTrue(answer.lost)
            assertEquals(1, a.getEventsSince(0L).size)
            assertEquals(JsonPrimitive("Newer"), a.getEventsSince(0L).single().mergedDocument()["title"])
        }

    @Test
    fun `an older clock still writes a field the other device never touched`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)
            val b = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(2_000L, 0, "dev-a"), set("title", "Newer")))
            val answer =
                push(b, patch("p2", Hlc.of(1_000L, 0, "dev-b"), set("description", "Older clock, untouched field")))

            assertTrue(answer.ok)
            assertFalse(answer.lost)
            val merged = a.getEventsSince(0L).last().mergedDocument()
            assertEquals(JsonPrimitive("Newer"), merged["title"])
            assertEquals(JsonPrimitive("Older clock, untouched field"), merged["description"])
        }

    @Test
    fun `the version counts the patches that landed`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)
            val b = server.client(ACCOUNT)

            val first = push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "One")))
            assertEquals(1L, first.newVersion)

            val second = push(b, patch("p2", Hlc.of(2_000L, 0, "dev-b"), set("description", "Two")))
            assertEquals(2L, second.newVersion)

            val lost = push(b, patch("p3", Hlc.of(500L, 0, "dev-b"), set("title", "Too old")))
            assertEquals(2L, lost.newVersion)
        }

    @Test
    fun `the same patch id twice is answered from the cache`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            val first = push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "One")))
            val second = push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "One")))

            assertTrue(second.cached)
            assertEquals(first.newVersion, second.newVersion)
            assertEquals(1, a.getEventsSince(0L).size)
        }

    @Test
    fun `a patch that writes a field the server owns is refused`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            val answer =
                push(
                    a,
                    patch(
                        "p1",
                        Hlc.of(1_000L, 0, "dev-a"),
                        FieldChange("serverVersion", FieldOp.SET, JsonPrimitive(7)),
                    ),
                )

            assertFalse(answer.ok)
            assertEquals(SyncProtocol.FIELD_NOT_WRITABLE, answer.error)
            assertTrue(a.getEventsSince(0L).isEmpty())
        }

    @Test
    fun `a patch that mixes a server-owned field with a writable one is refused whole and changes nothing`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            val answer =
                push(
                    a,
                    patch(
                        "p1",
                        Hlc.of(1_000L, 0, "dev-a"),
                        FieldChange("serverVersion", FieldOp.SET, JsonPrimitive(7)),
                        set("title", "Buy milk"),
                    ),
                )

            assertFalse(answer.ok)
            assertEquals(SyncProtocol.FIELD_NOT_WRITABLE, answer.error)
            assertTrue(a.getEventsSince(0L).isEmpty())
        }

    @Test
    fun `a patch without a clock is refused`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            val answer =
                push(
                    a,
                    DeltaPatch(
                        patchId = "p1",
                        entityId = ENTITY,
                        entityType = DocType.Task,
                        baseVersion = 0L,
                        ops = listOf(FieldChange("title", FieldOp.SET, JsonPrimitive("Buy milk"))),
                    ),
                )

            assertFalse(answer.ok)
            assertEquals(SyncProtocol.FIELD_NOT_WRITABLE, answer.error)
            assertTrue(a.getEventsSince(0L).isEmpty())
        }

    @Test
    fun `another account sees none of it`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))

            assertTrue(server.client("another-account").getEventsSince(0L).isEmpty())
        }

    @Test
    fun `two profiles keep two documents`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)
            val b = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Personal")), profileId = "prof-1")
            push(b, patch("p2", Hlc.of(2_000L, 0, "dev-b"), set("title", "Work")), profileId = "prof-2")

            val events = a.getEventsSince(0L)
            assertEquals(2, events.size)
            assertEquals("prof-1", events[0].profileId)
            assertEquals("prof-2", events[1].profileId)
            assertEquals(JsonPrimitive("Personal"), events[0].mergedDocument()["title"])
            assertEquals(JsonPrimitive("Work"), events[1].mergedDocument()["title"])
        }

    @Test
    fun `a delete patch writes a tombstone into the row and the feed carries it`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            val answer = push(a, deletePatch("p2", Hlc.of(2_000L, 0, "dev-a")))

            assertTrue(answer.ok)
            val events = a.getEventsSince(0L)
            assertEquals(2, events.size)
            val deletion = events.last()
            assertEquals(SyncEventType.DELETED, deletion.eventType)
            val doc = deletion.mergedDocument()
            // The deletion is a field in the merged row, not just an event type.
            assertEquals(JsonPrimitive(2_000L), doc["deletedAt"])
            // The row keeps what it held: the tombstone is a field, not a wipe.
            assertEquals(JsonPrimitive("Buy milk"), doc["title"])
        }

    @Test
    fun `an edit after a delete does not bring the row back`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            push(a, deletePatch("p2", Hlc.of(2_000L, 0, "dev-a")))
            val edit =
                push(
                    a,
                    patch(
                        "p3",
                        Hlc.of(3_000L, 0, "dev-b"),
                        set("description", "Edited after the delete"),
                    ),
                )

            assertTrue(edit.ok)
            assertFalse(edit.lost)
            val doc = a.getEventsSince(0L).last().mergedDocument()
            assertEquals(JsonPrimitive(2_000L), doc["deletedAt"])
            assertEquals(JsonPrimitive("Edited after the delete"), doc["description"])
        }

    @Test
    fun `a delete for a row the server never held creates the tombstone and delivers it`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            val answer = push(a, deletePatch("p1", Hlc.of(1_000L, 0, "dev-a")))

            assertTrue(answer.ok)
            assertNotNull(answer.newVersion)
            val event = a.getEventsSince(0L).single()
            assertEquals(JsonPrimitive(1_000L), event.mergedDocument()["deletedAt"])
        }

    @Test
    fun `a delete older than the stored one loses`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            push(a, deletePatch("p2", Hlc.of(3_000L, 0, "dev-a")))
            val older = push(a, deletePatch("p3", Hlc.of(2_000L, 0, "dev-b")))

            assertTrue(older.ok)
            assertTrue(older.lost)
            assertEquals(2, a.getEventsSince(0L).size)
            assertEquals(
                JsonPrimitive(3_000L),
                a.getEventsSince(0L).last().mergedDocument()["deletedAt"],
            )
        }

    @Test
    fun `an unset clears the field, and its clock keeps it cleared`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(2_000L, 0, "dev-a"), set("title", "Buy milk")))
            val cleared =
                push(
                    a,
                    patch("p2", Hlc.of(3_000L, 0, "dev-a"), FieldChange("title", FieldOp.UNSET)),
                )
            assertTrue(cleared.ok)
            assertFalse(cleared.lost)
            assertEquals(JsonNull, a.getEventsSince(0L).last().mergedDocument()["title"])

            val stale =
                push(a, patch("p3", Hlc.of(2_500L, 0, "dev-a"), set("title", "Older than the clear")))
            assertTrue(stale.lost)
            assertEquals(JsonNull, a.getEventsSince(0L).last().mergedDocument()["title"])
        }

    @Test
    fun `an append extends the list and a remove shrinks it`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), append("tags", "t1")))
            assertEquals(
                JsonArray(listOf(JsonPrimitive("t1"))),
                a.getEventsSince(0L).last().mergedDocument()["tags"],
            )

            push(a, patch("p2", Hlc.of(2_000L, 0, "dev-a"), append("tags", "t2")))
            assertEquals(
                JsonArray(listOf(JsonPrimitive("t1"), JsonPrimitive("t2"))),
                a.getEventsSince(0L).last().mergedDocument()["tags"],
            )

            push(a, patch("p3", Hlc.of(3_000L, 0, "dev-a"), remove("tags", "t1")))
            assertEquals(
                JsonArray(listOf(JsonPrimitive("t2"))),
                a.getEventsSince(0L).last().mergedDocument()["tags"],
            )
        }

    @Test
    fun `the event carries every field of the type, null where nothing was written`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))

            val doc = a.getEventsSince(0L).single().mergedDocument()
            assertTrue(doc.containsKey("description"))
            assertEquals(JsonNull, doc["description"])
            assertTrue(doc.containsKey("projectId"))
            assertEquals(JsonNull, doc["projectId"])
        }

    @Test
    fun `a delete patch with no clock is ordered by its timestamp`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            val answer = push(a, clocklessDeletePatch("p2", timestampMs = 2_000L))

            assertTrue(answer.ok)
            assertEquals(
                JsonPrimitive(2_000L),
                a.getEventsSince(0L).last().mergedDocument()["deletedAt"],
            )
        }

    @Test
    fun `a set of null on a field that was never written is not a change`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            val answer =
                push(
                    a,
                    patch("p1", Hlc.of(1_000L, 0, "dev-a"), FieldChange("title", FieldOp.SET, JsonNull)),
                )

            // The row already says "title holds nothing", so this patch changed
            // nothing: no version to report, no event on the feed.
            assertTrue(answer.ok)
            assertTrue(answer.lost)
            assertNull(answer.newVersion)
            assertTrue(a.getEventsSince(0L).isEmpty())
        }

    @Test
    fun `a set of null clears the field and its clock keeps it cleared`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            val cleared =
                push(
                    a,
                    patch("p2", Hlc.of(2_000L, 0, "dev-a"), FieldChange("title", FieldOp.SET, JsonNull)),
                )

            assertTrue(cleared.ok)
            assertFalse(cleared.lost)
            assertEquals(2L, cleared.newVersion)
            assertEquals(JsonNull, a.getEventsSince(0L).last().mergedDocument()["title"])

            // Cleared by `set null`, the field is as closed to an older clock as
            // it would be if it had been cleared by `unset`.
            val stale =
                push(a, patch("p3", Hlc.of(1_500L, 0, "dev-a"), set("title", "Older than the clear")))
            assertTrue(stale.lost)
            assertEquals(2L, stale.newVersion)
            assertEquals(JsonNull, a.getEventsSince(0L).last().mergedDocument()["title"])
        }

    @Test
    fun `an unset of a field cleared by set null is not a second change`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            push(a, patch("p2", Hlc.of(2_000L, 0, "dev-a"), FieldChange("title", FieldOp.SET, JsonNull)))

            // The clear and the unset are the same claim, so the second one has
            // nothing left to clear — whichever order they arrive in.
            val unset =
                push(a, patch("p3", Hlc.of(3_000L, 0, "dev-a"), FieldChange("title", FieldOp.UNSET)))
            assertTrue(unset.ok)
            assertTrue(unset.lost)
            assertEquals(2L, unset.newVersion)
            assertEquals(2, a.getEventsSince(0L).size)

            val setNull =
                push(
                    a,
                    patch("p4", Hlc.of(4_000L, 0, "dev-a"), FieldChange("title", FieldOp.SET, JsonNull)),
                )
            assertTrue(setNull.lost)
            assertEquals(2, a.getEventsSince(0L).size)
        }

    @Test
    fun `the feed keeps the only event that carries a row when it outgrows its bound`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            // Written once and never touched again: its event is the only word on
            // the row, so pruning must not take it however far behind the feed is.
            push(
                a,
                patch("frozen", Hlc.of(1_000L, 0, "dev-a"), set("title", "Untouched"), entityId = "t-frozen"),
            )
            // One row rewritten past the retention bound, so the feed is driven
            // over it by the same single row.
            val rewrites = 5_000
            repeat(rewrites) { i ->
                push(a, patch("hot-$i", Hlc.of(2_000L + i, 0, "dev-a"), set("title", "rewrite $i")))
            }

            // One pull, with room for the whole feed rather than the default page:
            // this is the below-the-floor puller, and what it is owed is a feed it
            // can converge from.
            val events = a.getEventsSince(0L, 10_000)

            // A puller from zero — every new device is one — still receives the
            // frozen row, and the rewritten row exactly once, as it now stands.
            assertEquals(1, events.count { it.entityId == "t-frozen" })
            assertEquals(4095, events.count { it.entityId == ENTITY })
            assertEquals(
                JsonPrimitive("rewrite ${rewrites - 1}"),
                events.last { it.entityId == ENTITY }.mergedDocument()["title"],
            )
            // The bound holds, because every event dropped above had a later
            // event for the same row still on the feed.
            assertTrue(events.size < rewrites)
        }

    private suspend fun push(
        client: SyncApiClient,
        patch: DeltaPatch,
        profileId: String = PROFILE,
    ): PatchResult =
        client
            .batchPush(
                BatchPushRequest(
                    deviceId = "dev",
                    profileId = profileId,
                    patches = listOf(patch),
                ),
            ).results
            .single()

    private fun patch(
        patchId: String,
        clock: Hlc,
        vararg ops: FieldChange,
        entityId: String = ENTITY,
    ): DeltaPatch =
        DeltaPatch(
            patchId = patchId,
            entityId = entityId,
            entityType = DocType.Task,
            baseVersion = 0L,
            ops = ops.toList(),
            hlc = clock,
        )

    private fun set(
        field: String,
        value: String,
    ): FieldChange = FieldChange(field, FieldOp.SET, JsonPrimitive(value))

    private fun append(
        field: String,
        value: String,
    ): FieldChange = FieldChange(field, FieldOp.APPEND, JsonPrimitive(value))

    private fun remove(
        field: String,
        value: String,
    ): FieldChange = FieldChange(field, FieldOp.REMOVE, JsonPrimitive(value))

    private fun deletePatch(
        patchId: String,
        clock: Hlc,
    ): DeltaPatch =
        DeltaPatch(
            patchId = patchId,
            entityId = ENTITY,
            entityType = DocType.Task,
            baseVersion = 0L,
            isDelete = true,
            hlc = clock,
        )

    private fun clocklessDeletePatch(
        patchId: String,
        timestampMs: Long,
    ): DeltaPatch =
        DeltaPatch(
            patchId = patchId,
            entityId = ENTITY,
            entityType = DocType.Task,
            baseVersion = 0L,
            isDelete = true,
            timestampMs = timestampMs,
        )

    private companion object {
        const val ACCOUNT = "account-a"
        const val ENTITY = "t1"
        const val PROFILE = "prof-1"
    }
}

private fun SyncEvent.mergedDocument(): JsonObject = data as JsonObject
