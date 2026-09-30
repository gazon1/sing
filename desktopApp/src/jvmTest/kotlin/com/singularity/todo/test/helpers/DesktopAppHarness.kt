package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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
    attempt: Int = 1,
    checkA11y: Boolean = false,
    test: suspend DesktopComposeUiTest.(koin: Koin) -> Unit,
) = runDesktopComposeUiTest {
    // Reset Kermit writer list and ring buffer before every test — each test
    // runs in its own forked JVM so there is no cross-test contamination, but
    // calling Logger.setLogWriters() multiple times within the same JVM without
    // clearing the list would double-add writers on the second invocation.
    resetKermitWriters()

    val app: KoinApplication = koinApplication {
        modules(
            coreLoggingModule(),
            *domainModule().toTypedArray(),
            // Loaded after domainModule() on purpose. Koin resolves duplicate
            // definitions last-wins, so coreModule()'s SupabaseAuthRepository
            // would otherwise override the fake here — and its userId resolves
            // from "anonymous" to a generated ULID a moment after startup, which
            // orphans anything written in that window and makes the row invisible
            // to every subsequent read.
            testPlatformModule(),
            testKermitModule(),
            gateModule(RELEASES_URL),
            overrides,
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

    try {
        test(app.koin)
    } catch (t: Throwable) {
        // Bundle first: screenshot, FakeAppDatabase state and the Kermit
        // ring-buffer, written to build/diagnostics/<TestClass>/attempt-N.
        val bundle = FailureBundle.capture(
            testClassSimpleName = testClassName,
            testInstance = this,
            app = app.koin,
            attempt = attempt,
            kermitBuffer = ringBuffer,
        )
        bundle.addSuppressedTo(t)

        // Then the semantics tree, attached to the failure rather than printed.
        // A `println` lands in stdout and gets lost when only the XML report is
        // read; a suppressed exception rides along with the stack trace in every
        // runner, which is the difference between re-running with a flag and
        // reading the report the run already produced.
        t.addSuppressed(AssertionError("Semantics tree at failure:\n${dumpSemantics()}"))
        throw t
    }

    // Runs only when the test body passed: a tree dumped mid-failure is already
    // captured by the bundle above, and the a11y verdict on a broken scene would
    // be noise. `awaitTagGone`-style waits the flow performed have already
    // settled the scene by here.
    if (checkA11y) {
        val violations = A11yChecker(this).scan()
        if (violations.isNotEmpty()) {
            throw AssertionError(
                buildString {
                    appendLine("Accessibility: ${violations.size} clickable node(s) announce nothing.")
                    appendLine("A screen reader has nothing to say for these; give each a text, a")
                    appendLine("contentDescription, or (if it is a test-only affordance) a testTag.")
                    violations.take(20).forEach(::appendLine)
                    if (violations.size > 20) appendLine("  ... and ${violations.size - 20} more")
                }.trimEnd(),
            )
        }
    }
}

/**
 * The current Compose semantics tree, or a note explaining why it could not be
 * read.
 *
 * Uses the unmerged tree: the whole point of debugging a selector is to see the
 * raw nodes before Compose folds them, and a merged tree hides exactly the
 * duplicate `Text` that makes `onNodeWithText` fail on ambiguity.
 */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.dumpSemantics(): String =
    runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 25) }
        .getOrElse { "<semantics tree unavailable: ${it.message}>" }
