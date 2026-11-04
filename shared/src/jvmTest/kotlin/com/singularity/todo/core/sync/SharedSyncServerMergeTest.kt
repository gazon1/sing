@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `an operation on a field the server owns is dropped and the rest of the patch lands`() =
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

            assertTrue(answer.ok)
            val merged = a.getEventsSince(0L).single().mergedDocument()
            assertEquals(JsonPrimitive("Buy milk"), merged["title"])
            assertFalse(merged.containsKey("serverVersion"))
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
    fun `a delete patch puts a deletion on the feed`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client(ACCOUNT)

            push(a, patch("p1", Hlc.of(1_000L, 0, "dev-a"), set("title", "Buy milk")))
            val answer =
                push(
                    a,
                    DeltaPatch(
                        patchId = "p2",
                        entityId = ENTITY,
                        entityType = DocType.Task,
                        baseVersion = 1L,
                        isDelete = true,
                    ),
                )

            assertTrue(answer.ok)
            val events = a.getEventsSince(0L)
            assertEquals(2, events.size)
            assertEquals(SyncEventType.DELETED, events.last().eventType)
        }

    private fun push(
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
    ): DeltaPatch =
        DeltaPatch(
            patchId = patchId,
            entityId = ENTITY,
            entityType = DocType.Task,
            baseVersion = 0L,
            ops = ops.toList(),
            hlc = clock,
        )

    private fun set(
        field: String,
        value: String,
    ): FieldChange = FieldChange(field, FieldOp.SET, JsonPrimitive(value))

    private companion object {
        const val ACCOUNT = "account-a"
        const val ENTITY = "t1"
        const val PROFILE = "prof-1"
    }
}

private fun SyncEvent.mergedDocument(): JsonObject = data as JsonObject
