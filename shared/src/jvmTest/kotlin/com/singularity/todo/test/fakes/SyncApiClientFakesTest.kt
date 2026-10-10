package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.PatchResult
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncEventType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SyncApiClientFakesTest {

    private fun patchResult(
        patchId: String,
        ok: Boolean = true,
        newVersion: Long? = null,
    ): PatchResult = PatchResult(
        patchId = patchId,
        ok = ok,
        newVersion = newVersion,
    )

    // ─── ScriptedSyncApiClient ────────────────────────────────────────────────

    @Test
    fun `ScriptedSyncApiClient — batchPush returns enqueued responses in order`() = runTest {
        val api = ScriptedSyncApiClient()
        api.enqueuePushResponse(patchResult("p1"))
        api.enqueuePushResponse(patchResult("p2"))

        val r1 = api.batchPush(BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()))
        val r2 = api.batchPush(BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()))

        assertEquals("p1", r1.results.single().patchId)
        assertEquals("p2", r2.results.single().patchId)
    }

    @Test
    fun `ScriptedSyncApiClient — falls back to defaultPushResponse when queue empty`() = runTest {
        val default = BatchPushResponse(listOf(patchResult("default")))
        val api = ScriptedSyncApiClient(defaultPushResponse = default)

        val r = api.batchPush(BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()))

        assertEquals("default", r.results.single().patchId)
    }

    @Test
    fun `ScriptedSyncApiClient — getEventsSince filters by serverLsn`() = runTest {
        val events = listOf(
            SyncEvent(
                serverLsn = 1,
                entityId = "e1",
                entityType = DocType.Task,
                eventType = SyncEventType.CREATED,
                data = null,
                createdAt = 1,
                profileId = "",
            ),
            SyncEvent(
                serverLsn = 5,
                entityId = "e2",
                entityType = DocType.Note,
                eventType = SyncEventType.UPDATED,
                data = null,
                createdAt = 5,
                profileId = "",
            ),
            SyncEvent(
                serverLsn = 10,
                entityId = "e3",
                entityType = DocType.Project,
                eventType = SyncEventType.DELETED,
                data = null,
                createdAt = 10,
                profileId = "",
            ),
        )
        val api = ScriptedSyncApiClient(pullEvents = events)

        val result = api.getEventsSince(sinceLsn = 3)

        assertEquals(2, result.size)
        assertEquals(5L, result[0].serverLsn)
        assertEquals(10L, result[1].serverLsn)
    }

    @Test
    fun `ScriptedSyncApiClient — ignoreSinceLsn ignores cursor`() = runTest {
        val events = listOf(
            SyncEvent(
                serverLsn = 1,
                entityId = "e1",
                entityType = DocType.Task,
                eventType = SyncEventType.CREATED,
                data = null,
                createdAt = 1,
                profileId = "",
            ),
        )
        val api = ScriptedSyncApiClient(pullEvents = events, ignoreSinceLsn = true)

        val result = api.getEventsSince(sinceLsn = 999)

        assertEquals(1, result.size)
        assertEquals(1L, result[0].serverLsn)
    }

    @Test
    fun `ScriptedSyncApiClient — enqueuePushResponse convenience overload`() = runTest {
        val api = ScriptedSyncApiClient()
        api.enqueuePushResponse(patchResult("p1"), patchResult("p2"))

        val r = api.batchPush(BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()))

        assertEquals(2, r.results.size)
        assertEquals("p1", r.results[0].patchId)
        assertEquals("p2", r.results[1].patchId)
    }

    // ─── RecordingSyncApiClient ───────────────────────────────────────────────

    @Test
    fun `RecordingSyncApiClient — batchPush records all calls`() = runTest {
        val api = RecordingSyncApiClient()
        val request1 = BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList())
        val request2 = BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList())

        api.batchPush(request1)
        api.batchPush(request2)

        assertEquals(2, api.pushCalls.size)
        assertSame(request1, api.pushCalls[0])
        assertSame(request2, api.pushCalls[1])
    }

    @Test
    fun `RecordingSyncApiClient — getEventsSince records all calls`() = runTest {
        val api = RecordingSyncApiClient()

        api.getEventsSince(sinceLsn = 0)
        api.getEventsSince(sinceLsn = 10)

        assertEquals(2, api.pullCalls.size)
        assertEquals("user-1" to 0L, api.pullCalls[0])
        assertEquals("user-1" to 10L, api.pullCalls[1])
    }

    @Test
    fun `RecordingSyncApiClient — reset clears recorded calls`() = runTest {
        val api = RecordingSyncApiClient()
        api.batchPush(BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()))
        assertEquals(1, api.pushCalls.size)

        api.reset()

        assertEquals(0, api.pushCalls.size)
        assertEquals(0, api.pullCalls.size)
    }

    @Test
    fun `RecordingSyncApiClient — returns configured response and events`() = runTest {
        val response = BatchPushResponse(listOf(patchResult("recorded")))
        val events = listOf(
            SyncEvent(
                serverLsn = 3,
                entityId = "e1",
                entityType = DocType.Tag,
                eventType = SyncEventType.CREATED,
                data = null,
                createdAt = 3,
                profileId = "",
            ),
        )
        val api = RecordingSyncApiClient(pushResponse = response, pullEvents = events)

        val pushResult = api.batchPush(
            BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()),
        )
        val pullResult = api.getEventsSince(sinceLsn = 0)

        assertEquals("recorded", pushResult.results.single().patchId)
        assertEquals(1, pullResult.size)
        assertEquals(3L, pullResult.first().serverLsn)
    }

    // ─── InterceptableSyncApiClient ───────────────────────────────────────────

    @Test
    fun `InterceptableSyncApiClient — delegates to underlying client`() = runTest {
        val inner = ScriptedSyncApiClient(
            defaultPushResponse = BatchPushResponse(listOf(patchResult("inner"))),
        )
        val api = InterceptableSyncApiClient(inner)

        val r = api.batchPush(
            BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()),
        )

        assertEquals("inner", r.results.single().patchId)
    }

    @Test
    fun `InterceptableSyncApiClient — failWith throws before delegate call`() = runTest {
        val inner = ScriptedSyncApiClient()
        val api = InterceptableSyncApiClient(inner)
        val ex = IllegalStateException("network")
        api.failWith = ex

        var caught: Throwable? = null
        try {
            api.batchPush(
                BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()),
            )
        } catch (e: Throwable) {
            caught = e
        }
        assertSame(ex, caught)
    }

    @Test
    fun `InterceptableSyncApiClient — onPushInFlight fires after batchPush returns`() = runTest {
        val inner = ScriptedSyncApiClient()
        val api = InterceptableSyncApiClient(inner)
        val captured = mutableListOf<BatchPushRequest>()

        api.onPushInFlight = { req -> captured.add(req) }

        api.batchPush(
            BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()),
        )

        assertEquals(1, captured.size)
    }

    @Test
    fun `InterceptableSyncApiClient — onBeforeResponseLoop fires after delegate returns`() = runTest {
        val inner = ScriptedSyncApiClient(
            defaultPushResponse = BatchPushResponse(listOf(patchResult("p1"))),
        )
        val api = InterceptableSyncApiClient(inner)
        val capturedResponse = mutableListOf<BatchPushResponse>()

        api.onBeforeResponseLoop = { _, response -> capturedResponse.add(response) }

        api.batchPush(
            BatchPushRequest(deviceId = "d", profileId = "p", patches = emptyList()),
        )

        assertEquals(1, capturedResponse.size)
        assertEquals("p1", capturedResponse[0].results.single().patchId)
    }

    @Test
    fun `InterceptableSyncApiClient — authenticatedAs is independent of delegate`() {
        val inner = ScriptedSyncApiClient(authenticatedAs = "inner-account")
        val api = InterceptableSyncApiClient(inner, authenticatedAs = "outer-account")

        assertEquals("outer-account", api.authenticatedAs)
    }
}
