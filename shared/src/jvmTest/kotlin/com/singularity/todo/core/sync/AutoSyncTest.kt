package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AutoSync] trigger fan-out logic.
 */
class AutoSyncTest {

    private fun fakePrefs(): FakeSyncPrefs = FakeSyncPrefs()
    private fun autoSync(prefs: FakeSyncPrefs, repo: FakeSyncRepository, scope: TestScope): AutoSync =
        AutoSync(prefs, repo, Logger.withTag("AutoSyncTest"))

    @Test
    fun `trigger ignores when autoSyncEnabled is false`() = runTest {
        val prefs = fakePrefs().also { runTest { it.setAutoSyncEnabled(false) } }
        val repo = FakeSyncRepository()
        val underTest = autoSync(prefs, repo, this)

        underTest.trigger(SyncTrigger.Created, this)
        advanceUntilIdle()

        assertFalse(repo.syncOnceCalled)
    }

    @Test
    fun `trigger ignores when trigger is not in enabledTriggers`() = runTest {
        val prefs = fakePrefs().also {
            runTest {
                it.setAutoSyncEnabled(true)
                it.setEnabledTriggers(emptySet())
            }
        }
        val repo = FakeSyncRepository()
        val underTest = autoSync(prefs, repo, this)

        underTest.trigger(SyncTrigger.Created, this)
        advanceUntilIdle()

        assertFalse(repo.syncOnceCalled)
    }

    @Test
    fun `trigger fires syncOnce when enabled and trigger is allowed`() = runTest {
        val prefs = fakePrefs().also {
            runTest {
                it.setAutoSyncEnabled(true)
                it.setEnabledTriggers(setOf(SyncTrigger.Created, SyncTrigger.Scheduled))
            }
        }
        val repo = FakeSyncRepository()
        val underTest = autoSync(prefs, repo, this)

        underTest.trigger(SyncTrigger.Scheduled, this)
        advanceUntilIdle()

        assertTrue(repo.syncOnceCalled)
    }

    @Test
    fun `trigger respects per-trigger toggles`() = runTest {
        val prefs = fakePrefs().also {
            runTest {
                it.setAutoSyncEnabled(true)
                it.setEnabledTriggers(setOf(SyncTrigger.AppResumed, SyncTrigger.AppSuspended))
            }
        }
        val repo = FakeSyncRepository()
        val underTest = autoSync(prefs, repo, this)

        underTest.trigger(SyncTrigger.Created, this)
        advanceUntilIdle()
        assertFalse(repo.syncOnceCalled)

        underTest.trigger(SyncTrigger.AppResumed, this)
        advanceUntilIdle()
        assertTrue(repo.syncOnceCalled)
    }

    @Test
    fun `all triggers are enabled by default`() {
        val prefs = fakePrefs()
        assertEquals(SyncTrigger.entries.toSet(), prefs.enabledTriggers)
    }
}
