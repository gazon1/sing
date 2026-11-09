@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import com.singularity.todo.test.fakes.SharedSyncServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The transport check: a push on one device reaches the other device of the
 * same account, carrying the merged document.
 *
 * The merge itself is proved in `SharedSyncServerMergeTest`; what is checked
 * here is that the fixture is wired the way the interface says it is — one
 * account's devices share a server, and the answer to a push is a real answer.
 */
@Tag("fast")
class SharedSyncServerSelfCheckTest {
    @Test
    fun `push on one client is visible to another client pull`() =
        runTest {
            val server = SharedSyncServer()
            val a = server.client("account-a")
            val b = server.client("account-a")

            val response =
                a.batchPush(
                    BatchPushRequest(
                        deviceId = "dev-a",
                        profileId = "prof-1",
                        patches = listOf(titlePatch("p1", "Buy milk")),
                    ),
                )
            assertTrue(response.results.single().ok)

            val events = b.getEventsSince(0L)
            assertEquals(1, events.size)
            assertEquals("t1", events.single().entityId)
            assertEquals(JsonPrimitive("Buy milk"), events.single().document()["title"])
        }

    private fun titlePatch(
        patchId: String,
        title: String,
    ): DeltaPatch =
        DeltaPatch(
            patchId = patchId,
            entityId = "t1",
            entityType = DocType.Task,
            baseVersion = 0L,
            ops = listOf(FieldChange("title", FieldOp.SET, JsonPrimitive(title))),
            hlc = Hlc.of(1_000L, 0, "dev-a"),
        )
}

private fun SyncEvent.document(): JsonObject = data as JsonObject
