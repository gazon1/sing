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
 */
@OptIn(ExperimentalTestApi::class)
fun runDesktopAppTest(
    overrides: Module = module {},
    test: suspend DesktopComposeUiTest.(koin: Koin) -> Unit,
) = runDesktopComposeUiTest {
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
            gateModule(RELEASES_URL),
            overrides,
        )
    }
    initTestLogging()
    setContent { KoinIsolatedContext(app) { App(deeplinkViewId = null, deeplinkTaskId = null) } }
    try {
        test(app.koin)
    } catch (t: Throwable) {
        // Attach the semantics tree to the failure rather than printing it. A
        // `println` lands in stdout and gets lost when only the XML report is
        // read; a suppressed exception rides along with the stack trace in every
        // runner, which is the difference between re-running with a flag and
        // reading the report the run already produced.
        t.addSuppressed(AssertionError("Semantics tree at failure:\n${dumpSemantics()}"))
        throw t
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
