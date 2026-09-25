package com.singularity.todo.core.sync

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for request coalescing in [FakeSyncRepository].
 *
 * FakeSyncRepository.syncOnce() mirrors the same coalescing guard the real
 * SyncRepositoryImpl uses: if status.isRunning() → return Skipped immediately.
 *
 * Real coalescing contract:
 * - syncOnce() while status is Idle → Success
 * - syncOnce() while status is Pushing/Pulling → Skipped (no concurrent run)
 * - Follow-up fires after completion if a trigger arrived during the run
 *
 * We test the core behavior using FakeSyncRepository directly.
 */
@Tag("slow")
class SyncRepositoryCoalescingTest {

    private fun createRepo(): FakeSyncRepository = FakeSyncRepository()

    // ─── Test 1: idle → Success ──────────────────────────────────────────────

    @Test
    fun `syncOnce while idle returns Success`() = runTest {
        val repo = createRepo()

        val outcome = repo.syncOnce()

        assertIs<SyncOutcome.Success>(outcome)
        assertEquals(1, repo.syncOnceCallCount)
    }

    // ─── Test 2: status is Running → Skipped, no concurrent call ─────────────

    @Test
    fun `syncOnce while status is Running returns Skipped`() = runTest {
        val repo = FakeSyncRepository().apply { syncOnceYields = true }

        // Launch first call — it suspends 10 ms with status = Pushing
        val firstCall = launch { repo.syncOnce() }
        // Advance virtual time so the first call starts and sets status = Pushing
        runCurrent()
        advanceUntilIdle()

        // Second call while status is Pushing → should return Skipped immediately
        val secondOutcome = repo.syncOnce()
        assertIs<SyncOutcome.Skipped>(secondOutcome)

        // First call completes successfully
        firstCall.join()
        assertIs<SyncOutcome.Success>(repo.syncOnceOutcome)
    }

    // ─── Test 3: syncOnceCallCount = 1 when second call is coalesced ──────────

    @Test
    fun `concurrent syncOnce calls — second coalesced, callCount is 1`() = runTest {
        val repo = FakeSyncRepository().apply { syncOnceYields = true }

        // Fire two syncOnce() calls concurrently
        val first = launch { repo.syncOnce() }
        // Advance to let the first call start and set status = Pushing
        runCurrent()
        advanceUntilIdle()
        val second = launch { repo.syncOnce() }

        first.join()
        second.join()

        // Exactly one engine call should have run
        assertEquals(1, repo.syncOnceCallCount)
    }

    // ─── Test 4: syncOnceYields=false → no status change, no coalescing ───────

    @Test
    fun `syncOnce without yields runs without changing status`() = runTest {
        val repo = createRepo() // syncOnceYields = false by default

        repo.syncOnce()
        repo.syncOnce()

        // Without yields, status never becomes Running, so no coalescing occurs
        assertEquals(2, repo.syncOnceCallCount)
        assertIs<SyncOutcome.Success>(repo.syncOnceOutcome)
    }
}
