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
 * Captures the test-class simple name of the currently executing JUnit 4 test.
 *
 * Walks the current thread's stack trace looking for the first frame whose
 * class name ends with `Test` (excluding this harness itself and any framework
 * classes). Used to name the diagnostics output directory.
 */
private fun currentTestClassSimpleName(): String {
    val frame = Thread.currentThread().stackTrace.firstOrNull { el ->
        el.className.contains("Test") &&
            !el.className.contains("Harness") &&
            !el.className.contains("runDesktopComposeUiTest") &&
            !el.className.contains("org.junit")
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
 * containing the merged and unmerged semantics trees, a screenshot, the
 * FakeAppDatabase state, and the Kermit ring-buffer log. The bundle path is
 * appended as a suppressed exception so it appears in the CI test report.
 */
@OptIn(ExperimentalTestApi::class)
fun runDesktopAppTest(
    overrides: Module = module {},
    attempt: Int = 1,
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
        val bundle = FailureBundle.capture(
            testClassSimpleName = testClassName,
            testInstance = this,
            app = app.koin,
            attempt = attempt,
            kermitBuffer = ringBuffer,
        )
        bundle.addSuppressedTo(t)
        throw t
    }
}
