@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.gate.presentation.viewmodel

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Tag

/**
 * [AppVersionGateViewModel] decides whether the app opens or shows a blocking
 * "update required" screen, so its comparison is the one path where a bug locks
 * every user out of the app.
 *
 * It had no test of its own. The only file in the tree that mentioned the class
 * was `FakeRemoteConfigPort` in the desktop test helpers — a *fake*, and it uses
 * the VM as documentation for what the fake is for. Nothing constructed it, so
 * neither branch of [AppVersionGateViewModel.evaluate] had ever run.
 *
 * The desktop flows depend on the default path: `FakeRemoteConfigPort` serves
 * `RemoteConfigSnapshot.defaults()` with `minSupportedVersion == null` so the
 * gate evaluates to `Allowed` and the app is usable in tests. That assumption is
 * what this file pins, from the other side as well.
 */
private class StubRemoteConfigPort(
    private var current: RemoteConfigSnapshot,
    private val refreshResult: Result<RemoteConfigSnapshot>? = null,
) : RemoteConfigPort {
    /** Plain counter, not a backing property: the rule wants a matching `refreshCalls` accessor. */
    var refreshCount = 0

    override suspend fun snapshot(): RemoteConfigSnapshot = current

    override suspend fun refresh(): Result<RemoteConfigSnapshot> {
        refreshCount++
        return refreshResult ?: Result.success(current)
    }

    // No underscore: the port's own KDoc calls this a StateFlow, and detekt's
    // BackingPropertyNaming wants a matching `observed` property for a `_observed`.
    private val observed = MutableStateFlow(current)
    override fun observe(): StateFlow<RemoteConfigSnapshot> = observed

    fun serve(snapshot: RemoteConfigSnapshot) {
        current = snapshot
        observed.value = snapshot
    }
}

private fun snapshotWithMinimum(min: AppVersion?) = RemoteConfigSnapshot.defaults()
    .copy(minSupportedVersion = min)

private const val PLAY_STORE_URL = "market://details?id=com.singularity.todo"

private val CURRENT = AppVersion("1.2.0", code = 12)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.newVm(
    port: RemoteConfigPort,
    current: AppVersion = CURRENT,
): Pair<AppVersionGateViewModel, AutoCloseableCoroutineScope> {
    val scope = testScope(this)
    val vm = AppVersionGateViewModel(
        remoteConfigPort = port,
        appVersion = current,
        playStoreUrl = PLAY_STORE_URL,
        scope = scope,
    )
    return vm to scope
}

@Tag("fast")
class AppVersionGateViewModelTest {

    @Test
    fun `allows the app when the config sets no minimum`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(null))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.state.value)
        scope.close()
    }

    @Test
    fun `blocks when the current version is below the minimum`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("2.0.0", code = 20)))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()

        val blocked = assertIs<AppVersionGateState.Blocked>(vm.state.value)
        assertEquals(AppVersion("2.0.0", code = 20), blocked.minSupportedVersion)
        assertEquals(CURRENT, blocked.currentVersion)
        assertEquals(PLAY_STORE_URL, blocked.updateUrl, "the block screen must offer the store URL")
        scope.close()
    }

    @Test
    fun `allows when the current version equals the minimum`() = runTest {
        // Boundary: `appVersion < min` is false for an equal version, so a
        // `<=` here would lock out every user on the exact minimum release.
        val port = StubRemoteConfigPort(snapshotWithMinimum(CURRENT))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.state.value)
        scope.close()
    }

    @Test
    fun `allows when the current version is above the minimum`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("1.0.0", code = 10)))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.state.value)
        scope.close()
    }

    @Test
    fun `comparison is by code and not by version name`() = runTest {
        // "1.10.0" sorts before "1.9.0" as a string but is the newer release.
        // AppVersion.compareTo documents code-first comparison; this pins it.
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("1.9.0", code = 9)))
        val (vm, scope) = newVm(port, current = AppVersion("1.10.0", code = 10))
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.state.value)
        scope.close()
    }

    @Test
    fun `CheckAgain re-reads the config and unblocks once the minimum is met`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("2.0.0", code = 20)))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()
        assertIs<AppVersionGateState.Blocked>(vm.state.value)

        // The user updates (or the config is rolled back) and taps "Check again".
        port.serve(snapshotWithMinimum(AppVersion("1.0.0", code = 10)))
        vm.onIntent(AppVersionGateIntent.CheckAgain)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.state.value)
        assertEquals(1, (port as StubRemoteConfigPort).refreshCount, "CheckAgain must force a refresh")
        scope.close()
    }

    @Test
    fun `a failed refresh falls back to defaults instead of leaving the screen on Checking`() = runTest {
        // The failure path resolves to `defaults()` (min == null → Allowed). The
        // alternative — staying on Checking — is a permanent spinner, and it is
        // the state a user is stranded in when the network is down.
        val port = StubRemoteConfigPort(
            current = snapshotWithMinimum(AppVersion("2.0.0", code = 20)),
            refreshResult = Result.failure(IllegalStateException("offline")),
        )
        val (vm, scope) = newVm(port)
        advanceUntilIdle()
        assertIs<AppVersionGateState.Blocked>(vm.state.value)

        vm.onIntent(AppVersionGateIntent.CheckAgain)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.state.value)
        scope.close()
    }
}
