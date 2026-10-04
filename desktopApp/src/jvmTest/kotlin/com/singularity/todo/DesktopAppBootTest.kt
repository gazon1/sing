package com.singularity.todo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The shell boots: the version gate passes, auth is satisfied, and the desktop
 * chrome renders around the default Today agenda.
 *
 * This is the desktop counterpart of `Maestro/flows/smoke/01-launch-today.yaml`
 * and the first test to fail if the app's startup chain breaks. It exists as a
 * Deliberate checkpoint: ADR `2026-09-06-desktop-smoke-test-with-koin` recorded
 * that `NavBackStackEntry` lifecycle transitions crash the Compose test host, and
 * its smoke test was later removed, leaving the question open. Mounting the real
 * `App()` retires that risk for the whole flow suite.
 *
 * ## Two ways to see the tree, and they are not redundant
 *
 * *When a test fails*, `runDesktopAppTest` already attaches the semantics tree
 * to the exception, so it rides along in the report — no flag needed.
 *
 * *When a test passes but you want to know what is on screen*, re-run with
 * `-Dsingularity.ui.dumpTree=true` and this test prints it between
 * `=== SEMANTICS TREE ===` markers.
 *
 * Prefer either to guessing a selector from source — see the probe habit in
 * `singularity-todo-maestro-flows`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class DesktopAppBootTest {

    @Test
    fun shell_boots_into_the_today_agenda() = runDesktopAppTest {
        waitForIdle()

        if (System.getProperty(DUMP_TREE_PROPERTY) == "true") {
            println("=== SEMANTICS TREE START ===")
            println(onRoot().printToString(maxDepth = 12))
            println("=== SEMANTICS TREE END ===")
        }

        // Past the version gate and auth: the agenda's own empty state is only
        // rendered once AppVersionGateScreen resolves to Allowed and AuthGuard
        // lets its content through.
        assertTextDisplayed("No tasks")

        // The desktop chrome, not the Android bottom bar.
        assertContentDescriptionDisplayed(DesktopShell.HAMBURGER)
        assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)
        assertCurrentTab("Today")

        // The agenda top bar's own actions.
        assertTagDisplayed(TestTags.AGENDA_SAVED_VIEWS_BUTTON)
        assertTagDisplayed(TestTags.AGENDA_SAVE_CURRENT_BUTTON)
    }

    private companion object {
        const val DUMP_TREE_PROPERTY = "singularity.ui.dumpTree"
    }
}
