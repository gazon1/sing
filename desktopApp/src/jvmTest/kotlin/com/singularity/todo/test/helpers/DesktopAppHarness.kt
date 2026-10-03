package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.singularity.todo.App
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.gate.gateModule
import org.koin.compose.KoinIsolatedContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

/**
 * Captures the test-class simple name of the currently executing test.
 *
 * Matching on "the class name contains Test" is not good enough: between the
 * test body and the runner sit several framework frames that also contain it —
 * Compose's synthetic `SkikoComposeUiTest$runTest$1$1$…` and kotlinx-coroutines'
 * `TestDispatcher`. Picking any of those writes every failure in the suite into
 * one directory, and under the parallel test executor the bundles overwrite
 * each other.
 *
 * So the predicate is inverted: take the first frame that belongs to this
 * project's own test code and is not the harness itself.
 */
private fun currentTestClassSimpleName(): String {
    val frame = Thread.currentThread().stackTrace.firstOrNull { el ->
        val name = el.className
        name.startsWith("com.singularity.todo") && !name.contains("test.helpers")
    }
    return frame?.className?.substringAfterLast('.')?.removeSuffix("$") ?: "UnknownTest"
}

/**
 * Mounts the production [App] composable inside a headless
 * [runDesktopComposeUiTest] — the desktop mirror of a Maestro flow.
 *
 * The Koin graph is assembled per test rather than through `startKoin`, so no
 * process-wide singleton leaks between test classes. It mirrors
 * `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`, except that
 * [testPlatformModule] replaces the real `platformModule()` so nothing reads or
 * writes the developer's `~/.singularity-todo` and no test shells out to
 * `secret-tool` or `notify-send`.
 *
 * [overrides] loads last and therefore wins Koin's last-definition-wins rule,
 * which is how a test swaps a binding for a fake.
 *
 * On test failure, a [FailureBundle] is captured to `build/diagnostics/<TestClass>/`
 * containing a screenshot, the FakeAppDatabase state and the Kermit ring-buffer
 * log. The bundle path is appended as a suppressed exception so it appears in the
 * CI test report, alongside the semantics tree.
 *
 * [checkA11y] runs an accessibility pass over the merged semantics tree after
 * the test body succeeds, failing on clickable nodes that announce nothing. It
 * is opt-in per flow: switching it on everywhere would surface the whole
 * backlog at once, which is how these checks get abandoned. It lives here
 * rather than in an `AfterEachCallback` because the scene is gone by the time a
 * callback runs.
 */
@OptIn(ExperimentalTestApi::class)
fun runDesktopAppTest(
    overrides: Module = module {},
    checkA11y: Boolean = false,
    test: suspend DesktopComposeUiTest.(koin: Koin) -> Unit,
) = runDesktopComposeUiTest {
    // Reset Kermit writer list and ring buffer before every test.
    // With forkEvery=1 each test class runs in its own JVM, but resetKermitWriters()
    // is called unconditionally so the code is correct regardless of fork policy:
    // calling Logger.setLogWriters() without clearing the list would double-add
    // writers on the second invocation within the same JVM.
    resetKermitWriters()

    val app: KoinApplication = koinApplication {
        modules(
            listOf(coreLoggingModule()) +
                domainModule() +
                // Loaded after domainModule() on purpose. Koin resolves duplicate
                // definitions last-wins, so coreModule()'s SupabaseAuthRepository
                // would otherwise override the fake here — and its userId resolves
                // from "anonymous" to a generated ULID a moment after startup, which
                // orphans anything written in that window and makes the row invisible
                // to every subsequent read.
                listOf(testPlatformModule(), testKermitModule(), gateModule(RELEASES_URL)) +
                listOf(overrides),
        )
    }

    // Install the per-test ring buffer into Kermit before the test body runs so
    // all log entries from the entire test (including Compose boot) are captured.
    val ringBuffer: RingBufferLogWriter = app.koin.get()
    ringBuffer.reset()
    installRingBuffer(ringBuffer)
    initTestLogging()

    setContent { KoinIsolatedContext(app) { App(deeplinkViewId = null, deeplinkTaskId = null) } }

    val testClassName = currentTestClassSimpleName()

    // Step recorder lives across retries so the full step history is available on failure.
    val recorder = StepRecorder()
    setStepRecorder(recorder)

    var lastThrowable: Throwable? = null
    var passedOnRetry = false

    // Read retry policy from system properties (passed via -D from gradle, e.g.
    // -Dretry.maxAttempts=2 -Dretry.failOnPassedAfterRetry=false). When not set,
    // defaults to one attempt with strict failure reporting (no masking).
    val maxAttempts = (System.getProperty("retry.maxAttempts") ?: "1").toIntOrNull() ?: 1
    val failOnPassedAfterRetry = (System.getProperty("retry.failOnPassedAfterRetry") ?: "true").toBoolean()

    try {
        repeat(maxAttempts) { attemptIndex ->
            val currentAttempt = 1 + attemptIndex
            try {
                test(app.koin)
                if (currentAttempt > 1) passedOnRetry = true
                lastThrowable = null
                return@runDesktopComposeUiTest
            } catch (t: Throwable) {
                lastThrowable = t
                // Bundle first: screenshot, FakeAppDatabase state and the Kermit
                // ring-buffer, written to build/diagnostics/<TestClass>/attempt-N.
                val bundle = FailureBundle.capture(
                    testClassSimpleName = testClassName,
                    testInstance = this,
                    app = app.koin,
                    attempt = currentAttempt,
                    kermitBuffer = ringBuffer,
                    steps = recorder,
                    highlightTag = recorder.lastFailedStepDetail(),
                )
                bundle.addSuppressedTo(t)

                // Last steps summary rides on the failure for CI visibility without
                // opening the bundle directory.
                val lastSteps = recorder.summary()
                if (lastSteps.isNotEmpty()) {
                    t.addSuppressed(AssertionError("Last steps:\n$lastSteps"))
                }

                // Then the semantics tree, attached to the failure rather than printed.
                // A `println` lands in stdout and gets lost when only the XML report is
                // read; a suppressed exception rides along with the stack trace in every
                // runner, which is the difference between re-running with a flag and
                // reading the report the run already produced.
                t.addSuppressed(AssertionError("Semantics tree at failure:\n${dumpSemantics()}"))

                // If more attempts remain, re-run without propagating the failure yet.
                if (attemptIndex < maxAttempts - 1) {
                    // Continue to next attempt
                } else {
                    throw t
                }
            }
        }

        // All attempts exhausted without a definitive pass/fail — decide based on policy.
        if (passedOnRetry && !failOnPassedAfterRetry) {
            // Test passed on retry: suppress failure, report as green.
            return@runDesktopComposeUiTest
        }
        throw lastThrowable ?: error("unreachable")
    } finally {
        clearStepRecorder()
    }
}
