package com.singularity.todo.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every desktop UI test that calls `runDesktopComposeUiTest` must go through
 * [runDesktopAppTest][com.singularity.todo.test.helpers.runDesktopAppTest] or
 * a documented lighter harness.
 *
 * ## The defect this catches
 *
 * `runDesktopAppTest` (DesktopAppHarness) captures FailureBundle on every test
 * failure: screenshot + FakeAppDatabase state + Kermit ring-buffer log, all written
 * to `build/diagnostics/<TestClass>/`. The bundle path rides the JUnit XML report
 * as a suppressed exception, so a CI failure includes the diagnostics without
 * re-running with a flag.
 *
 * A test that calls `runDesktopComposeUiTest` directly bypasses the harness and
 * loses this capture. Its assertion failures produce only the stack trace and the
 * Compose error message — no screenshot, no DB state, no log — which makes
 * post-mortem debugging significantly harder.
 *
 * This is not hypothetical. `NotesScreenTest` and `TagsRenameUiTest` were
 * confirmed to call `runDesktopComposeUiTest` directly during the ultron-ideas
 * epic; both were written before `DesktopAppHarness` existed and have since been
 * migrated to `IsolatedComposeTest`.
 *
 * ## The fix
 *
 * Migrate the offending test to `runDesktopAppTest { koin -> ... }` (full Koin
 * graph, for integration tests) or to `runIsolatedComposeTest { ... }` (no Koin,
 * for pure presentational tests). Both live in `test/helpers/`.
 *
 * ## Allowlist
 *
 * Every entry is **documented debt**, not a permanent exception. Each carries a
 * reason and a link to the issue or phase that will address it. Entries must be
 * removed when the migration is complete; stale entries hide the same regression
 * the test was meant to catch.
 *
 * @see com.singularity.todo.test.helpers.runDesktopAppTest
 * @see com.singularity.todo.test.helpers.IsolatedComposeTest
 */
class DesktopTestHarnessEnforcementTest {

    /**
     * The desktop app module's jvmTest root.
     *
     * Passed as `desktopAppJvmTest.root` system property from shared/build.gradle.kts.
     * Using a system property (rather than computing from `commonMain.root`) avoids
     * fragile relative-path logic across different worktree layouts.
     */
    private val desktopAppJvmTestRoot: File
        get() = File(
            System.getProperty("desktopAppJvmTest.root")
                ?: error("desktopAppJvmTest.root is not set — see shared/build.gradle.kts"),
        )

    /**
     * Files that currently call `runDesktopComposeUiTest` directly.
     *
     * Each entry is debt: migrate the file to `runDesktopAppTest` or
     * `IsolatedComposeTest`, then remove the entry.
     *
     * Format: `fileName` to `reason`.
     */
    private val allowedBypassFiles = mapOf(
        "MenuBarTest.kt" to
            "smoke tests: no real AWT Frame in test, harness would add " +
            "FailureBundle machinery with no actionable output. " +
            "Revisit if smoke scope expands.",
        "ContextMenuTest.kt" to
            "smoke tests: desktop popup menus cannot be fully tested in headless " +
            "environment, harness would add FailureBundle with no actionable output. " +
            "Revisit if smoke scope expands.",
    )

    @Test
    fun every_desktop_test_goes_through_a_harness() {
        val desktopTestRoot = desktopAppJvmTestRoot
        if (!desktopTestRoot.exists()) {
            fail(
                "Desktop app jvmTest root does not exist at ${desktopTestRoot.absolutePath}. " +
                    "Has the desktopApp module been renamed?",
            )
        }

        val bypassFiles = desktopTestRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // The harness itself is allowed.
            .filter { it.name != "DesktopAppHarness.kt" }
            // IsolatedComposeTest is the documented lighter harness for pure Compose tests.
            .filter { it.name != "IsolatedComposeTest.kt" }
            // Check for direct calls to the deprecated and v2 entry points.
            .filter { file ->
                file.readLines().any { line ->
                    line.contains("runDesktopComposeUiTest")
                }
            }
            .filter { it.name !in allowedBypassFiles.keys }
            .toList()

        if (bypassFiles.isEmpty()) return

        val fileList = bypassFiles.joinToString("\n") { "  ${it.name}" }
        val allowedList = allowedBypassFiles.entries.joinToString("\n") { (name, reason) ->
            "  $name — $reason"
        }
        fail(
            buildString {
                appendLine("Desktop UI tests that call runDesktopComposeUiTest directly:")
                appendLine(fileList)
                appendLine()
                appendLine("These lose FailureBundle (screenshot, DB state, Kermit log) on failure.")
                appendLine("Migrate them to runDesktopAppTest { koin -> ... } or")
                appendLine("IsolatedComposeTest { ... }, then remove from this list.")
                appendLine()
                appendLine("Allowlisted bypasses (documented debt):")
                appendLine(allowedList)
            },
        )
    }
}
