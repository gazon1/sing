package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
 * @param width The width of the test window in pixels. The default is the
 *   project's usual 1024. A narrower window makes a full-width component — a
 *   month grid, say — fit on the screen: a grid whose cells are a seventh of
 *   the window width is taller than the window, and the cells below the fold
 *   cannot be clicked at all.
 * @param height The height of the test window in pixels.
 *
 * @see runDesktopAppTest
 * @see com.singularity.todo.test.helpers.IsolatedComposeTest
 */
@OptIn(ExperimentalTestApi::class)
fun runIsolatedComposeTest(
    width: Int = 1024,
    height: Int = 768,
    test: suspend DesktopComposeUiTest.() -> Unit,
) = runDesktopComposeUiTest(width = width, height = height) {
    try {
        test()
    } catch (t: Throwable) {
        // Attach the unmerged semantics tree as a suppressed exception.
        // A bare `println` is lost when only the XML report is read; a suppressed
        // exception rides the stack trace into every runner's report.
        val tree = runCatching {
            semanticsTree(maxDepth = 25)
        }.getOrElse { "<semantics tree unavailable: ${it.message}>" }
        t.addSuppressed(AssertionError("Semantics tree at failure:\n$tree"))
        throw t
    }
}
