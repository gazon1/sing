package com.singularity.todo.flows

import org.junit.jupiter.api.Tag
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
@Tag("slow")
class DesktopTestHarnessEnforcementTest {

    /**
     * This module's own jvmTest sources.
     *
     * Was `System.getProperty("desktopAppJvmTest.root")`, passed from
     * `shared/build.gradle.kts`, back when this test lived in `:shared`. The
     * property existed because the test was in one module enforcing another's
     * conventions, and a path across module boundaries cannot be written
     * relatively. Moved here, that problem is gone and so is the property: the
     * relative form is the same one `HarnessConventionTest` already uses, and it
     * works because the test JVM's working directory is the module directory.
     *
     * Scans the whole `jvmTest` tree, not just `flows/`, because a bypassing test
     * is not required to live in this package.
     */
    private val desktopAppJvmTestRoot: File = File("src/jvmTest/kotlin")

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
        "CelebrationTest.kt" to
            "haptic verification tests call runDesktopComposeUiTest to trigger " +
            "LaunchedEffect and verify side-effects on a test spy. " +
            "Should migrate to v2 runDesktopComposeUiTest with aligned dispatchers " +
            "so advanceUntilIdle() is scoped to the same test. " +
            " tracked in follow-up ADR.",
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
            // This file, too, and not because it is allowed to bypass — because it
            // NAMES the entry point it forbids. Its KDoc has to, to explain the
            // rule, and so does the `contains` call below. Found by planting a
            // violating file: without this the gate reports itself, which is the
            // most disorienting possible failure for a rule about bypassing the
            // harness.
            .filter { it.name != "DesktopTestHarnessEnforcementTest.kt" }
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
