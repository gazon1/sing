package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest

/**
 * A lightweight harness for desktop Compose UI tests that do **not** require Koin.
 *
 * Unlike [runDesktopAppTest] which mounts the full application graph (Koin, database,
 * all ports), this wraps only `runDesktopComposeUiTest` with two things:
 * 1. A suppressed [AssertionError] carrying the unmerged semantics tree on failure.
 * 2. A `@OptIn(ExperimentalTestApi::class)` on the caller's behalf.
 *
 * Use this for pure presentational tests — composables that can be driven with
 * [setContent][androidx.compose.ui.test.ComposeUiTest.setContent] and need no
 * repository, ViewModel, or Koin wiring.
 *
 * **What is NOT captured here** (that [runDesktopAppTest] provides):
 * - Screenshot (`captureToImage`) — the scene has no app chrome, visuals are
 *   unreadable without the full app shell.
 * - FakeAppDatabase state dump — there is no database in an isolated test.
 * - Kermit ring-buffer log — there is no Koin app, hence no log writer.
 *
 * For integration tests that need Koin, database, or FailureBundle capture,
 * use [runDesktopAppTest] instead.
 *
 * @see runDesktopAppTest
 * @see com.singularity.todo.test.helpers.IsolatedComposeTest
 */
@OptIn(ExperimentalTestApi::class)
fun runIsolatedComposeTest(
    test: suspend DesktopComposeUiTest.() -> Unit,
) = runDesktopComposeUiTest {
    try {
        test()
    } catch (t: Throwable) {
        // Attach the unmerged semantics tree as a suppressed exception.
        // A bare `println` is lost when only the XML report is read; a suppressed
        // exception rides the stack trace into every runner's report.
        val tree = runCatching {
            onRoot(useUnmergedTree = true).printToString(maxDepth = 25)
        }.getOrElse { "<semantics tree unavailable: ${it.message}>" }
        t.addSuppressed(AssertionError("Semantics tree at failure:\n$tree"))
        throw t
    }
}
