@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.FakeSyncPrefs
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.feature.sync.presentation.SyncIntent
import com.singularity.todo.feature.sync.presentation.SyncViewModel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SyncViewModel].
 *
 * The VM's init block launches collectors that run for the VM's lifetime.
 * A child-Job [testScope] ensures cancelling VM coroutines does NOT cancel the test body.
 * Each test calls [AutoCloseableCoroutineScope.job.cancel] after assertions
 * to cleanly shut down VM coroutines before the test scope cleanup phase.
 */
@Tag("fast")
class SyncViewModelTest {

    private fun createVm(
        repo: FakeSyncRepository = FakeSyncRepository(),
        prefs: FakeSyncPrefs = FakeSyncPrefs(),
        scope: TestScope,
    ): Pair<SyncViewModel, AutoCloseableCoroutineScope> {
        // Child-Job wrapper: cancelling it stops the VM's infinite collectors
        // without cancelling the test body. Each test MUST cancel it before returning.
        val vmScope = testScope(scope)
        return SyncViewModel(repo, prefs, vmScope) to vmScope
    }

    // ─── Test 1: syncNow debounce when already loading ─────────────────────────
    //
    // With syncOnceYields=true the first call suspends 10 ms. The second call
    // sees isLoading=true inside the launch and is debounced (returns early).
    // Debounce guard: if (isLoading || status.isRunning()) return@launch

    @Test
    fun `syncNow while first sync is suspended calls syncOnce exactly once`() = runTest {
        val repo = FakeSyncRepository().apply { syncOnceYields = true }
        val prefs = FakeSyncPrefs()
        val (vm, vmScope) = createVm(repo, prefs, this)

        vm.onIntent(SyncIntent.SyncNow)
        runCurrent() // first launch runs: sets isLoading, syncOnce suspends in delay(10)
        vm.onIntent(SyncIntent.SyncNow) // second launch sees isLoading=true → debounced
        advanceTimeBy(1_000)
        runCurrent() // let the first (yielding) syncOnce() finish

        assertEquals(1, repo.syncOnceCallCount)
        vmScope.job?.cancel()
    }

    // ─── Test 2: syncNow debounce when status is already running ───────────────
    //
    // Status starts as Pulling (isRunning=true). The guard blocks the call.

    @Test
    fun `syncNow when status is running is debounced`() = runTest {
        val repo = FakeSyncRepository()
        val prefs = FakeSyncPrefs()
        repo.setStatus(SyncEngineStatus.Pulling)
        val (vm, vmScope) = createVm(repo, prefs, this)

        vm.onIntent(SyncIntent.SyncNow)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, repo.syncOnceCallCount)
        vmScope.job?.cancel()
    }

    // ─── Test 3: AcknowledgeError clears errorMessage and connectionTestResult ─
    //
    // Verify that AcknowledgeError sets both fields to null directly in state.

    @Test
    fun `AcknowledgeError clears errorMessage and connectionTestResult`() = runTest {
        val repo = FakeSyncRepository()
        val prefs = FakeSyncPrefs()
        val (vm, vmScope) = createVm(repo, prefs, this)

        // Trigger a successful sync so the VM's state is populated.
        vm.onIntent(SyncIntent.SyncNow)
        advanceTimeBy(1_000)
        runCurrent()

        // Call AcknowledgeError — it should clear both fields unconditionally.
        vm.onIntent(SyncIntent.AcknowledgeError)

        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.connectionTestResult)
        vmScope.job?.cancel()
    }

    // ─── Test 4: TestConnection → Success ─────────────────────────────────────
    //
    // After TestConnection completes, isTestingConnection=false and result=Success.

    @Test
    fun `TestConnection final state is isTestingConnection=false and Success result`() = runTest {
        val repo = FakeSyncRepository()
        val prefs = FakeSyncPrefs()
        val (vm, vmScope) = createVm(repo, prefs, this)

        vm.onIntent(SyncIntent.TestConnection)
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(vm.state.value.isTestingConnection)
        assertEquals(ConnectionTestResult.Success, vm.state.value.connectionTestResult)
        vmScope.job?.cancel()
    }

    // ─── Test 5: TestConnection → Failure ─────────────────────────────────────
    //
    // Override testConnection() to return a failure; verify the error message.

    @Test
    fun `TestConnection Failure maps to ConnectionTestResult_Failure with correct message`() = runTest {
        val repo = object : FakeSyncRepository() {
            override suspend fun testConnection(): ConnectionTestResult =
                ConnectionTestResult.Failure(AppError.Validation("Invalid token"))
        }
        val prefs = FakeSyncPrefs()
        val (vm, vmScope) = createVm(repo, prefs, this)

        vm.onIntent(SyncIntent.TestConnection)
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(vm.state.value.isTestingConnection)
        val result = vm.state.value.connectionTestResult
        assertTrue(result is ConnectionTestResult.Failure)
        assertEquals("Invalid token", result.error.message)
        vmScope.job?.cancel()
    }
}
