package com.singularity.todo.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import org.junit.jupiter.api.Tag

@Tag("fast")
class SyncOutboxClaimFakeTest {
    @Test
    fun getPending_excludesClaimedRows() = runTest {
        val dao = FakeSyncOutboxDao()
        val now = 1000L
        dao.insert(
            SyncOutboxEntity(
                patchId = "p1",
                ownerId = "o",
                profileId = "pr",
                entityId = "e",
                entityType = "t",
                payload = "{}",
                createdAt = 1L,
            ),
        )
        dao.insert(
            SyncOutboxEntity(
                patchId = "p2",
                ownerId = "o",
                profileId = "pr",
                entityId = "e2",
                entityType = "t",
                payload = "{}",
                createdAt = 2L,
            ),
        )
        dao.claim(listOf("p1"), "token-1")
        val pending = dao.getPending(now, "o", "pr")
        assertEquals(listOf("p2"), pending.map { it.patchId })
    }

    @Test
    fun claim_setsClaimToken() = runTest {
        val dao = FakeSyncOutboxDao()
        val token = "token-1"
        dao.insert(
            SyncOutboxEntity(
                patchId = "p1",
                ownerId = "o",
                profileId = "pr",
                entityId = "e",
                entityType = "t",
                payload = "{}",
                createdAt = 1L,
            ),
        )
        dao.claim(listOf("p1"), token)
        assertTrue(dao.rows.first().claimToken == token)
    }

    @Test
    fun markFailed_clearsClaimToken() = runTest {
        val dao = FakeSyncOutboxDao()
        val now = 1000L
        dao.insert(
            SyncOutboxEntity(
                patchId = "p1",
                ownerId = "o",
                profileId = "pr",
                entityId = "e",
                entityType = "t",
                payload = "{}",
                createdAt = 1L,
                claimToken = "token-1",
            ),
        )
        dao.markFailed("p1", "err", now + 100, "token-1")
        assertEquals(null, dao.rows.first().claimToken)
    }

    @Test
    fun claim_doesNotOverwriteAnExistingClaim() = runTest {
        val dao = FakeSyncOutboxDao()
        val first = "token-1"
        val second = "token-2"
        dao.insert(
            SyncOutboxEntity(
                patchId = "p1",
                ownerId = "o",
                profileId = "pr",
                entityId = "e",
                entityType = "t",
                payload = "{}",
                createdAt = 1L,
            ),
        )
        dao.claim(listOf("p1"), first)
        dao.claim(listOf("p1"), second)
        assertEquals(first, dao.rows.first().claimToken)
    }

    @Test
    fun markFailed_doesNotClearAnotherCyclesClaim() = runTest {
        val dao = FakeSyncOutboxDao()
        dao.insert(
            SyncOutboxEntity(
                patchId = "p1",
                ownerId = "o",
                profileId = "pr",
                entityId = "e",
                entityType = "t",
                payload = "{}",
                createdAt = 1L,
                claimToken = "token-B",
            ),
        )
        dao.markFailed("p1", "err", 2000L, "token-A")
        assertEquals("token-B", dao.rows.first().claimToken)
    }

    @Test
    fun release_doesNotClearAnotherCyclesClaim() = runTest {
        val dao = FakeSyncOutboxDao()
        dao.insert(
            SyncOutboxEntity(
                patchId = "p1",
                ownerId = "o",
                profileId = "pr",
                entityId = "e",
                entityType = "t",
                payload = "{}",
                createdAt = 1L,
                claimToken = "token-B",
            ),
        )
        dao.release(listOf("p1"), "token-A")
        assertEquals("token-B", dao.rows.first().claimToken)
    }
}
