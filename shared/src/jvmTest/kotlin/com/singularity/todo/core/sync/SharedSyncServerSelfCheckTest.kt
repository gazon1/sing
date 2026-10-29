@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedSyncServerSelfCheckTest {
    @Test
    fun `push on one client is visible to another client pull`() = runTest {
        val server = SharedSyncServer()
        val a = server.client("account-a")
        val b = server.client("account-b")

        val patch = DeltaPatch(
            patchId = "p1",
            entityId = "t1",
            entityType = DocType.Task,
            baseVersion = 0L,
        )
        val resp = a.batchPush(BatchPushRequest(deviceId = "dev-a", profileId = "prof-1", patches = listOf(patch)))
        assertTrue(resp.results.single().ok)

        val events = b.getEventsSince(0L)
        assertEquals(1, events.size)
        assertEquals("t1", events.single().entityId)
    }
}
