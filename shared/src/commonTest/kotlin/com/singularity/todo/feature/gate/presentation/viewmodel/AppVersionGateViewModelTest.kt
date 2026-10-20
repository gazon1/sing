@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.gate.presentation.viewmodel

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

/**
 * A config source whose read **throws** rather than returning a failed [Result].
 *
 * The two are not interchangeable here: the version gate has to fail open on both, and they
 * reach the funnel by different routes. `AppVersionGateViewModel` documents that split — a
 * thrown read lands in the funnel's error arm, a returned failure never throws at all — so a
 * test that only exercised one of them would leave the other route unproven.
 */
private class ThrowingRemoteConfigPort(private val error: Throwable) : RemoteConfigPort {
    override suspend fun snapshot(): RemoteConfigSnapshot = throw error

    override suspend fun refresh(): Result<RemoteConfigSnapshot> = throw error

    private val observed = MutableStateFlow(RemoteConfigSnapshot.defaults())
    override fun observe(): StateFlow<RemoteConfigSnapshot> = observed
}

/**
 * A config source whose read **never completes**.
 *
 * The other half of the fail-open policy. A read that throws is a broken config server; a read
 * that hangs is a slow *local* one — a Room database being created on first launch, a locked
 * multi-instance database. Nothing about it throws, so nothing about it reports itself: the gate
 * simply stays on `Checking`, which is a full-screen splash in front of the whole app.
 */
private class HangingRemoteConfigPort(
    private val served: RemoteConfigSnapshot = RemoteConfigSnapshot.defaults(),
    private val hangOnSnapshot: Boolean = true,
    private val hangOnRefresh: Boolean = true,
) : RemoteConfigPort {
    override suspend fun snapshot(): RemoteConfigSnapshot =
        if (hangOnSnapshot) awaitCancellation() else served

    override suspend fun refresh(): Result<RemoteConfigSnapshot> =
        if (hangOnRefresh) awaitCancellation() else Result.success(served)

    private val observed = MutableStateFlow(served)
    override fun observe(): StateFlow<RemoteConfigSnapshot> = observed
}

private const val PLAY_STORE_URL = "market://details?id=com.singularity.todo"

private val CURRENT = AppVersion("1.2.0", code = 12)

/** Substring of the bypass breadcrumb; the invariant, not the wording. */
private const val BYPASS_MARKER = "bypassed"

/** Substring that names the reason: the read missed its deadline. */
private const val TIMED_OUT_MARKER = "timed out"

/** Substring of the grouping key the timeout is reported under. */
private const val TIMEOUT_KEY_MARKER = "timeout"

/** Substring that names the reason: the read failed outright. */
private const val UNREADABLE_MARKER = "unreadable"

/**
 * The deadline the tests inject.
 *
 * Small enough to be obvious and short enough to be quick even if a test ever runs it on real
 * time; the injected value is what proves the gate gives up on its own schedule rather than on
 * the production one.
 */
private const val TEST_READ_TIMEOUT_MS = 50L

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.newVm(
    port: RemoteConfigPort,
    current: AppVersion = CURRENT,
    reporter: CrashReportingPort? = null,
    readTimeoutMs: Long = TEST_READ_TIMEOUT_MS,
): Pair<AppVersionGateViewModel, AutoCloseableCoroutineScope> {
    val scope = testScope(this)
    val vm = AppVersionGateViewModel(
        remoteConfigPort = port,
        appVersion = current,
        playStoreUrl = PLAY_STORE_URL,
        scope = scope,
        // A no-op reporter is the default precisely so that a VM built in a test is silent.
        // These assertions are about what reached the reporting layer, so they must opt in
        // to a recording one explicitly rather than inherit the silence.
        crashReporter = reporter ?: com.singularity.todo.core.observability.NoOpCrashReportingPort(),
        readTimeoutMs = readTimeoutMs,
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

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `blocks when the current version is below the minimum`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("2.0.0", code = 20)))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()

        val blocked = assertIs<AppVersionGateState.Blocked>(vm.stateFlow.value)
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

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `allows when the current version is above the minimum`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("1.0.0", code = 10)))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `comparison is by code and not by version name`() = runTest {
        // "1.10.0" sorts before "1.9.0" as a string but is the newer release.
        // AppVersion.compareTo documents code-first comparison; this pins it.
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("1.9.0", code = 9)))
        val (vm, scope) = newVm(port, current = AppVersion("1.10.0", code = 10))
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `CheckAgain re-reads the config and unblocks once the minimum is met`() = runTest {
        val port = StubRemoteConfigPort(snapshotWithMinimum(AppVersion("2.0.0", code = 20)))
        val (vm, scope) = newVm(port)
        advanceUntilIdle()
        assertIs<AppVersionGateState.Blocked>(vm.stateFlow.value)

        // The user updates (or the config is rolled back) and taps "Check again".
        port.serve(snapshotWithMinimum(AppVersion("1.0.0", code = 10)))
        vm.onIntent(AppVersionGateIntent.CheckAgain)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
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
        assertIs<AppVersionGateState.Blocked>(vm.stateFlow.value)

        vm.onIntent(AppVersionGateIntent.CheckAgain)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    // ─── The fail-open record (#144) ───────────────────────────────────────────────
    //
    // Failing open is correct here and invisible: `Allowed(defaults)` is byte-for-byte
    // what a healthy read produces. The report says the read failed; only the breadcrumb
    // says the gate was let through anyway. That record is the entire point of the
    // change, and it shipped with no test — a breadcrumb asserted only by reading the
    // code is not a breadcrumb.

    @Test
    fun `a throwing read is reported`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(ThrowingRemoteConfigPort(IllegalStateException("config down")), reporter = reporter)
        advanceUntilIdle()

        assertEquals(1, reporter.reports.size, "the unreadable config must be reported, not swallowed")
        scope.close()
    }

    @Test
    fun `a throwing read leaves a record that the gate was bypassed`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(ThrowingRemoteConfigPort(IllegalStateException("config down")), reporter = reporter)
        advanceUntilIdle()

        // The whole point: a healthy read and a bypassed outage produce the same state,
        // so without this record an outage reads as a healthy dashboard.
        assertEquals(1, reporter.breadcrumbs.size, "failing open must leave exactly one bypass record")
        assertTrue(
            reporter.breadcrumbs.single().contains(BYPASS_MARKER),
            "the record must say the gate was bypassed, got: ${reporter.breadcrumbs.single()}",
        )
        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `the bypass record is attached to the report it explains`() = runTest {
        // THE assertion. The backend attaches the breadcrumb buffer to a report as it is at
        // the moment the report is made, so a record written *after* the report travels with
        // the next event instead — and if nothing else fails, it travels nowhere.
        //
        // Two separate lists cannot catch this: a correctly ordered pair and a reversed one
        // both produce one report and one breadcrumb. This is the failure the missing test
        // was filed for, and it was real.
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(ThrowingRemoteConfigPort(IllegalStateException("config down")), reporter = reporter)
        advanceUntilIdle()

        val breadcrumbAt = reporter.breadcrumbIndexContaining(BYPASS_MARKER)
        val reportAt = reporter.reportIndexWithKey(reporter.reports.single().second)
        assertTrue(
            breadcrumbAt in 0 until reportAt,
            "the bypass record must be written BEFORE the report, so it rides along with it. " +
                "Recorded order was: ${reporter.events}",
        )
        scope.close()
    }

    @Test
    fun `a healthy read leaves no bypass record`() = runTest {
        // The other direction. A record that fires on the happy path is noise that trains
        // a reader to ignore it, which is the same blindness the record was added to remove.
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(StubRemoteConfigPort(snapshotWithMinimum(null)), reporter = reporter)
        advanceUntilIdle()

        assertTrue(reporter.breadcrumbs.isEmpty(), "a healthy read must not record a bypass: ${reporter.breadcrumbs}")
        assertTrue(reporter.reports.isEmpty(), "a healthy read must not report: ${reporter.reports}")
        scope.close()
    }

    @Test
    fun `a returned refresh failure is reported and bypassed too`() = runTest {
        // The other route to the same state. `refresh()` returns a failed Result rather than
        // throwing, so it never reaches the funnel's error arm at all — the original code
        // reported and breadcrumbmed it by hand, which is exactly the place the ordering
        // could drift without the thrown path noticing.
        val reporter = RecordingCrashReportingPort()
        val port = StubRemoteConfigPort(
            current = snapshotWithMinimum(null),
            refreshResult = Result.failure(IllegalStateException("offline")),
        )
        val (vm, scope) = newVm(port, reporter = reporter)
        advanceUntilIdle()

        vm.onIntent(AppVersionGateIntent.CheckAgain)
        advanceUntilIdle()

        assertEquals(1, reporter.reports.size, "a returned failure is still a failure and must be reported")
        val breadcrumbAt = reporter.breadcrumbIndexContaining(BYPASS_MARKER)
        val reportAt = reporter.reportIndexWithKey(reporter.reports.single().second)
        assertTrue(
            breadcrumbAt in 0 until reportAt,
            "the bypass record must precede the report on the returned-failure route too. " +
                "Recorded order was: ${reporter.events}",
        )
        scope.close()
    }

    // ─── The read deadline ───────────────────────────────────────────────────────
    //
    // The fail-open policy above covers a read that *fails*. A read that never returns is the
    // same defect with a longer fuse, and it is not covered by anything: nothing throws, so
    // nothing reports, and the gate stays on `Checking` — a full-screen splash in front of the
    // whole app. A slow local read (a Room database created on first launch, a locked
    // multi-instance database) is enough to get there.

    @Test
    fun `a read that never returns is admitted on the defaults once the deadline passes`() = runTest {
        // The state itself is the whole point: `Allowed(defaults)` instead of `Checking`, which
        // would leave the user in front of a splash with no way past it.
        val (vm, scope) = newVm(HangingRemoteConfigPort())
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `the deadline the gate gives up on is the one that was injected`() = runTest {
        // Without this, a test that waits for the production deadline would pass just as
        // happily — virtual time makes five seconds as instant as fifty milliseconds, so the
        // only way to know which deadline was honoured is to look at the clock.
        //
        // The lower bound matters as much as the upper one: a gate that never gives up never
        // waits at all, so zero virtual time is the shape of the defect this change removes,
        // not a fast success.
        val (vm, scope) = newVm(HangingRemoteConfigPort())
        advanceUntilIdle()

        assertTrue(
            testScheduler.currentTime in 1..TEST_READ_TIMEOUT_MS,
            "the gate must give up at the injected deadline, not after it and not never: " +
                "virtual time was ${testScheduler.currentTime} ms",
        )
        scope.close()
    }

    @Test
    fun `a read that never returns is reported`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(HangingRemoteConfigPort(), reporter = reporter)
        advanceUntilIdle()

        assertEquals(1, reporter.reports.size, "a read that never returns is a failure and must be reported")
        assertTrue(
            reporter.reports.single().second.contains(TIMEOUT_KEY_MARKER),
            "the report must group the timeout apart from every other failed read, " +
                "got key: ${reporter.reports.single().second}",
        )
        scope.close()
    }

    @Test
    fun `a read that never returns leaves a record that the gate was bypassed`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(HangingRemoteConfigPort(), reporter = reporter)
        advanceUntilIdle()

        // The whole point of the record: a healthy read and a bypassed one produce the same
        // state, so without this an outage reads as a healthy dashboard.
        assertEquals(1, reporter.breadcrumbs.size, "failing open must leave exactly one bypass record")
        assertTrue(
            reporter.breadcrumbs.single().contains(BYPASS_MARKER),
            "the record must say the gate was bypassed, got: ${reporter.breadcrumbs.single()}",
        )
        scope.close()
    }

    @Test
    fun `the bypass record of a timed-out read names the deadline`() = runTest {
        // The reason is part of the record. "The gate was let through" and "the gate was let
        // through because the read never came back" are two different investigations: the first
        // points at the config server, the second at whatever the read was waiting on.
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(HangingRemoteConfigPort(), reporter = reporter)
        advanceUntilIdle()

        val breadcrumb = reporter.breadcrumbs.single()
        assertTrue(breadcrumb.contains(TIMED_OUT_MARKER), "the record must name the timeout, got: $breadcrumb")
        assertFalse(breadcrumb.contains(UNREADABLE_MARKER), "the record must not blame the config, got: $breadcrumb")
        scope.close()
    }

    @Test
    fun `the bypass record of a timed-out read rides along with the report`() = runTest {
        // The same ordering the thrown path has: the record is written before the report, so
        // the backend attaches it to the report it explains. A timeout with no next event
        // would otherwise travel nowhere.
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(HangingRemoteConfigPort(), reporter = reporter)
        advanceUntilIdle()

        val breadcrumbAt = reporter.breadcrumbIndexContaining(BYPASS_MARKER)
        val reportAt = reporter.reportIndexWithKey(reporter.reports.single().second)
        assertTrue(
            breadcrumbAt in 0 until reportAt,
            "the bypass record must be written BEFORE the report, so it rides along with it. " +
                "Recorded order was: ${reporter.events}",
        )
        scope.close()
    }

    @Test
    fun `a thrown read is still recorded as an unreadable config`() = runTest {
        // The deadline must not have changed the existing path: the same read, the same
        // wording, the same fail-open outcome. Without this, a timeout-specific wording could
        // quietly swallow the thrown case.
        val reporter = RecordingCrashReportingPort()
        val (vm, scope) = newVm(ThrowingRemoteConfigPort(IllegalStateException("config down")), reporter = reporter)
        advanceUntilIdle()

        val breadcrumb = reporter.breadcrumbs.single()
        assertTrue(
            breadcrumb.contains(UNREADABLE_MARKER),
            "the record must name the unreadable config, got: $breadcrumb",
        )
        assertFalse(breadcrumb.contains(TIMED_OUT_MARKER), "a thrown read did not time out, got: $breadcrumb")
        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        scope.close()
    }

    @Test
    fun `a refresh that never returns is admitted on the defaults`() = runTest {
        // The same deadline on the second path. The blocked screen is a full-screen splash too,
        // and a refresh that never returns leaves the user on `Checking` with nothing left to
        // press but the button they already pressed.
        val reporter = RecordingCrashReportingPort()
        val port = HangingRemoteConfigPort(
            served = snapshotWithMinimum(AppVersion("2.0.0", code = 20)),
            hangOnSnapshot = false,
            hangOnRefresh = true,
        )
        val (vm, scope) = newVm(port, reporter = reporter)
        advanceUntilIdle()
        assertIs<AppVersionGateState.Blocked>(vm.stateFlow.value)

        vm.onIntent(AppVersionGateIntent.CheckAgain)
        advanceUntilIdle()

        assertIs<AppVersionGateState.Allowed>(vm.stateFlow.value)
        assertTrue(
            reporter.breadcrumbs.any { it.contains(TIMED_OUT_MARKER) },
            "the timed-out refresh must leave a record naming the timeout: ${reporter.breadcrumbs}",
        )
        scope.close()
    }
}
