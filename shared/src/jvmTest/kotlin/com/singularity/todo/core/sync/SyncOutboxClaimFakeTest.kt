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
        dao.claim(listOf("p1"), now)
        val pending = dao.getPending(now, "o", "pr")
        assertEquals(listOf("p2"), pending.map { it.patchId })
    }

    @Test
    fun claim_setsClaimedAt() = runTest {
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
        dao.claim(listOf("p1"), now)
        assertTrue(dao.rows.first().claimedAt == now)
    }

    @Test
    fun markFailed_clearsClaimedAt() = runTest {
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
                claimedAt = now,
            ),
        )
        dao.markFailed("p1", "err", now + 100, now)
        assertEquals(null, dao.rows.first().claimedAt)
    }

    @Test
    fun claim_doesNotOverwriteAnExistingClaim() = runTest {
        val dao = FakeSyncOutboxDao()
        val first = 1000L
        val second = 2000L
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
        assertEquals(first, dao.rows.first().claimedAt)
    }
}
