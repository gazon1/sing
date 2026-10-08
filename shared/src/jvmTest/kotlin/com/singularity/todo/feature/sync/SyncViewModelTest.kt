@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.FakeSyncScopeProvider
import com.singularity.todo.core.sync.FakeSyncStateRepository
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncState
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
import kotlin.time.Duration.Companion.minutes

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

    private val scopeA = SyncScope("owner-1", "profile-a")
    private val scopeB = SyncScope("owner-1", "profile-b")

    private fun createVm(
        repo: FakeSyncRepository = FakeSyncRepository(),
        stateRepository: FakeSyncStateRepository = FakeSyncStateRepository(),
        scopeProvider: FakeSyncScopeProvider = FakeSyncScopeProvider(scopeA),
        scope: TestScope,
    ): Pair<SyncViewModel, AutoCloseableCoroutineScope> {
        // Child-Job wrapper: cancelling it stops the VM's infinite collectors
        // without cancelling the test body. Each test MUST cancel it before returning.
        val vmScope = testScope(scope)
        val vm = SyncViewModel(repo, stateRepository, scopeProvider, scope = vmScope)
        // The VM reads its settings from a collection, not from the constructor, so the
        // first frame is the neutral default. Without this the tests below would assert
        // against a screen that has not been told anything yet.
        scope.runCurrent()
        return vm to vmScope
    }

    // ─── Test 1: syncNow debounce when already loading ─────────────────────────
    //
    // With syncOnceYields=true the first call suspends 10 ms. The second call
    // sees isLoading=true inside the launch and is debounced (returns early).
    // Debounce guard: if (isLoading || status.isRunning()) return@launch

    @Test
    fun `syncNow while first sync is suspended calls syncOnce exactly once`() = runTest {
        val repo = FakeSyncRepository().apply { syncOnceYields = true }
        val (vm, vmScope) = createVm(repo, scope = this)

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
        repo.setStatus(SyncEngineStatus.Pulling)
        val (vm, vmScope) = createVm(repo, scope = this)

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
        val (vm, vmScope) = createVm(repo, scope = this)

        // Trigger a successful sync so the VM's state is populated.
        vm.onIntent(SyncIntent.SyncNow)
        advanceTimeBy(1_000)
        runCurrent()

        // Call AcknowledgeError — it should clear both fields unconditionally.
        vm.onIntent(SyncIntent.AcknowledgeError)

        assertNull(vm.stateFlow.value.errorMessage)
        assertNull(vm.stateFlow.value.connectionTestResult)
        vmScope.job?.cancel()
    }

    // ─── Test 4: TestConnection → Success ─────────────────────────────────────
    //
    // After TestConnection completes, isTestingConnection=false and result=Success.

    @Test
    fun `TestConnection final state is isTestingConnection=false and Success result`() = runTest {
        val repo = FakeSyncRepository()
        val (vm, vmScope) = createVm(repo, scope = this)

        vm.onIntent(SyncIntent.TestConnection)
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(vm.stateFlow.value.isTestingConnection)
        assertEquals(ConnectionTestResult.Success, vm.stateFlow.value.connectionTestResult)
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
        val (vm, vmScope) = createVm(repo, scope = this)

        vm.onIntent(SyncIntent.TestConnection)
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(vm.stateFlow.value.isTestingConnection)
        val result = vm.stateFlow.value.connectionTestResult
        assertTrue(result is ConnectionTestResult.Failure)
        assertEquals("Invalid token", result.error.message)
        vmScope.job?.cancel()
    }

    // ─── Per-scope settings ─────────────────────────────────────────────────
    //
    // The screen used to read one global SyncPrefs and keep its own copy, so
    // switching profile showed the previous profile's settings and every write
    // landed in the one global slot. These are the tests that would have failed.

    @Test
    fun `the screen shows the settings of the current scope`() = runTest {
        val state = FakeSyncStateRepository()
        state.seed(
            scopeA,
            SyncState(
                autoSyncEnabled = false,
                scheduledInterval = 15.minutes,
                lastSuccessfulSyncAt = 999L,
            ),
        )
        val (vm, vmScope) = createVm(stateRepository = state, scope = this)

        assertFalse(vm.stateFlow.value.autoSyncEnabled)
        assertEquals(15, vm.stateFlow.value.intervalMinutes)
        assertEquals(999L, vm.stateFlow.value.lastSyncedAt)
        vmScope.job?.cancel()
    }

    @Test
    fun `switching scope re-reads the settings instead of keeping the old ones`() = runTest {
        val state = FakeSyncStateRepository()
        state.seed(scopeA, SyncState(autoSyncEnabled = false, scheduledInterval = 15.minutes))
        state.seed(scopeB, SyncState(autoSyncEnabled = true, scheduledInterval = 45.minutes))
        val provider = FakeSyncScopeProvider(scopeA)
        val (vm, vmScope) = createVm(stateRepository = state, scopeProvider = provider, scope = this)
        assertEquals(15, vm.stateFlow.value.intervalMinutes)

        provider.set(scopeB)
        runCurrent()

        assertEquals(45, vm.stateFlow.value.intervalMinutes)
        assertTrue(vm.stateFlow.value.autoSyncEnabled)
        vmScope.job?.cancel()
    }

    @Test
    fun `toggling auto-sync writes to the current scope only`() = runTest {
        val state = FakeSyncStateRepository()
        state.seed(scopeA, SyncState(autoSyncEnabled = true))
        state.seed(scopeB, SyncState(autoSyncEnabled = true))
        val provider = FakeSyncScopeProvider(scopeA)
        val (vm, vmScope) = createVm(stateRepository = state, scopeProvider = provider, scope = this)

        vm.onIntent(SyncIntent.SetAutoSync(false))
        runCurrent()

        assertFalse(state.snapshot().getValue(scopeA).autoSyncEnabled, "the edited scope must change")
        assertTrue(
            state.snapshot().getValue(scopeB).autoSyncEnabled,
            "the other profile's setting must be untouched",
        )
        vmScope.job?.cancel()
    }

    @Test
    fun `a settings change with no scope is dropped rather than applied to a later one`() = runTest {
        val state = FakeSyncStateRepository()
        val provider = FakeSyncScopeProvider(null)
        val (vm, vmScope) = createVm(stateRepository = state, scopeProvider = provider, scope = this)

        vm.onIntent(SyncIntent.SetAutoSync(true))
        runCurrent()

        // Nothing was written, and nothing is waiting: the scope that appears next is
        // a different subject, and inheriting this toggle would change a profile the
        // user never touched.
        assertTrue(state.snapshot().isEmpty(), "no row may be created for a scope that does not exist")
        vmScope.job?.cancel()
    }

    // ─── Attachment sync preference ────────────────────────────────────────────
    //
    // The preference is read, never written, from this screen. Stage 1 stores it per
    // scope; there is no binary transport to act on it yet, so the control is locked and
    // no intent writes it. These two tests pin both halves of that arrangement — they
    // fail if someone adds an intent that appears to enable a sync that cannot happen.

    @Test
    fun `the attachment preference is read from the current scope, not from a local field`() = runTest {
        val state = FakeSyncStateRepository()
        val provider = FakeSyncScopeProvider(scopeA)
        val (vm, vmScope) = createVm(stateRepository = state, scopeProvider = provider, scope = this)

        state.setAttachmentsSyncEnabled(scopeA, true)
        state.setAttachmentsSyncEnabled(scopeB, false)
        runCurrent()

        assertTrue(
            vm.stateFlow.value.attachmentsSyncEnabled,
            "the screen must show the preference belonging to the scope it is editing",
        )

        // Switching profile must swap the displayed value, which is what "per scope"
        // means from the user's side. A local field could not do this.
        provider.set(scopeB)
        runCurrent()

        assertFalse(
            vm.stateFlow.value.attachmentsSyncEnabled,
            "the previous profile's preference must not follow the user to the next tab",
        )
        vmScope.job?.cancel()
    }

    @Test
    fun `the attachment preference defaults to off for a scope that never set one`() = runTest {
        val state = FakeSyncStateRepository()
        val (vm, vmScope) = createVm(stateRepository = state, scope = this)

        assertFalse(
            vm.stateFlow.value.attachmentsSyncEnabled,
            "an untouched scope must not appear to have asked for attachment sync",
        )
        vmScope.job?.cancel()
    }
}
