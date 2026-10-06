package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The runner's *behaviour*, not its wiring.
 *
 * `SyncPeriodicTriggerWiringTest` already covers the bug this class's own history is
 * about: that the trigger on the JVM is a real one and not a no-op behind a type test.
 * That is one question. This file asks the three that remain, and they are the ones the
 * rest of the scheduling work will be built on:
 *
 * - does periodic sync start and stop with the session and the active profile (CO-08);
 * - are the settings re-read per emission, so a changed interval takes effect and a
 *   profile with auto-sync off does not inherit one that has it on (CO-09);
 * - and is the scope that starts it the scope that emitted (CO-12).
 *
 * A wrong answer to any of them is invisible from outside — auto-sync runs at the wrong
 * cadence, or does not run — and none is what the wiring test looks at.
 */
@Tag("fast")
class SyncRunnerTest {

    @Test
    fun `auto-sync starts for a scope whose settings enable it`() = runTest {
        val trigger = RecordingTrigger()
        val scope = SyncScope("owner-1", "profile-1")
        val state = FakeSyncStateRepository().apply {
            setAutoSyncEnabled(scope, true)
            setScheduledInterval(scope, 15.minutes)
        }
        runner(trigger, state, FakeSyncScopeProvider(scope))

        runCurrent()

        assertEquals(listOf(15.minutes), trigger.started, "the scope's own interval, not a default")
    }

    @Test
    fun `auto-sync stops when the scope goes away`() = runTest {
        val trigger = RecordingTrigger()
        val scope = SyncScope("owner-1", "profile-1")
        val state = FakeSyncStateRepository().apply { setAutoSyncEnabled(scope, true) }
        val scopeProvider = FakeSyncScopeProvider(scope)
        runner(trigger, state, scopeProvider)

        runCurrent()
        scopeProvider.set(null) // signed out, or no profile
        runCurrent()

        assertEquals(1, trigger.stops, "a signed-out device must not keep a periodic trigger running")
    }

    @Test
    fun `a profile with auto-sync off does not inherit one that has it on`() = runTest {
        // The case that justifies watching the scope rather than the session. Two
        // profiles of one account, one on and one off: a trigger that ignores the
        // profile means the second profile syncs on the first one's schedule, or the
        // first stops syncing because the second is not interested.
        val trigger = RecordingTrigger()
        val on = SyncScope("owner-1", "profile-a")
        val off = SyncScope("owner-1", "profile-b")
        val state = FakeSyncStateRepository().apply {
            setAutoSyncEnabled(on, true)
            setAutoSyncEnabled(off, false)
        }
        val scopeProvider = FakeSyncScopeProvider(on)
        runner(trigger, state, scopeProvider)

        runCurrent()
        assertEquals(1, trigger.started.size)

        scopeProvider.set(off)
        runCurrent()
        assertEquals(
            1,
            trigger.stops,
            "switching to a profile with auto-sync off must stop the trigger it was running",
        )

        scopeProvider.set(on)
        runCurrent()
        assertEquals(2, trigger.started.size, "switching back must start it again")
    }

    @Test
    fun `a new interval takes effect when the scope moves`() = runTest {
        val trigger = RecordingTrigger()
        val first = SyncScope("owner-1", "profile-1")
        val second = SyncScope("owner-1", "profile-2")
        val state = FakeSyncStateRepository().apply {
            setAutoSyncEnabled(first, true)
            setScheduledInterval(first, 15.minutes)
            setAutoSyncEnabled(second, true)
            setScheduledInterval(second, 30.minutes)
        }
        val scopeProvider = FakeSyncScopeProvider(first)
        runner(trigger, state, scopeProvider)

        runCurrent()
        scopeProvider.set(second)
        runCurrent()

        assertEquals(
            listOf(15.minutes, 30.minutes),
            trigger.started,
            "the settings are read per emission, so a scope change reads the new scope's",
        )
    }

    @Test
    fun `a settings-only change does not reach the runner, and that is a defect`() = runTest {
        // Named as a defect because it is one, and kept as a test because the fix must
        // not be a passing test that only passes after the fix.
        //
        // The runner reads the settings per emission, which looks like it re-reads them
        // often enough to notice a change. It does not: the only thing it collects is
        // the active scope, and a `StateFlow` does not emit when the value it is set to
        // equals the value it already holds. Changing the sync interval — the one
        // setting on the screen that exists to be changed — therefore does nothing
        // until the profile is switched or the app restarts.
        //
        // The test plan expects this to take effect (CO-09). The fix is to also
        // collect the settings for the active scope, which is a behaviour change and
        // wants a decision rather than a drive-by: #186.
        val trigger = RecordingTrigger()
        val scope = SyncScope("owner-1", "profile-1")
        val state = FakeSyncStateRepository().apply {
            setAutoSyncEnabled(scope, true)
            setScheduledInterval(scope, 15.minutes)
        }
        val scopeProvider = FakeSyncScopeProvider(scope)
        runner(trigger, state, scopeProvider)

        runCurrent()
        state.setScheduledInterval(scope, 30.minutes)
        runCurrent()

        assertEquals(
            listOf(15.minutes),
            trigger.started,
            "today the change does not reach the runner. When #186 is fixed this " +
                "assertion becomes the one that must fail, and the expected value " +
                "becomes [15m, 30m].",
        )
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    /** A trigger that records what it was asked and how often. */
    private class RecordingTrigger : SyncPeriodicTrigger {
        val started = mutableListOf<Duration>()
        var startCalls = 0
        var stops = 0

        override fun start(interval: Duration) {
            started += interval
            startCalls++
        }

        override fun stop() {
            stops++
        }
    }

    private fun TestScope.runner(
        trigger: RecordingTrigger,
        state: FakeSyncStateRepository,
        scopeProvider: FakeSyncScopeProvider,
    ): SyncRunner {
        // A child of the test's background scope, so the runner's init collector —
        // which never completes by design — is cancelled when the test ends instead of
        // leaving runTest waiting on it.
        val scope = testScope(backgroundScope)
        val shadow = FakeSyncShadowDao()
        val engine = SyncEngine(
            log = Logger.withTag("SyncRunnerTest"),
            api = FakeSyncApiClient(),
            authRepository = FakeSyncAuthRepository(Session.SignedOut),
            outboxDao = FakeSyncOutboxDao(),
            deadLetterDao = FakeSyncDeadLetterDao(),
            idGenerator = SequentialIdGenerator(),
            stateRepository = state,
            scopeProvider = scopeProvider,
            shadowDao = shadow,
            patchBuilder = fakeSyncPatchBuilder(shadow),
            writer = fakeSyncDocumentWriter(),
            scheduler = FakeSyncWorkScheduler(),
            clock = MutableClock(),
            scope = scope,
            crashReporter = NoOpCrashReportingPort(),
        )
        return SyncRunner(
            engine = engine,
            coordinator = SyncCoordinator(
                // Never runs: nothing in this file asks for a cycle. The runner
                // refuses one while signed out, and these tests are about scheduling.
                runCycle = { SyncOutcome.Skipped("not exercised here") },
                scope = scope,
            ),
            periodicTrigger = trigger,
            authRepository = FakeSyncAuthRepository(Session.SignedOut),
            stateRepository = state,
            scopeProvider = scopeProvider,
            scope = scope,
        )
    }
}

private typealias Duration = kotlin.time.Duration
