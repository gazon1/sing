package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.seedTask
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/tasks/01-set-priority.yaml`.
 *
 * Exists in the same MR that applies `TASK_EDITOR_PRIORITY_ROW` and
 * `PRIORITY_OPTION_*` to the composables, per the rule that a tag is added
 * together with the flow that consumes it. A tag with no flow is ballast — see
 * ADR `2026-09-30-desktop-compose-ui-flow-tests.md`.
 *
 * Both selectors are keyed on stable ids, not on the visible label: the row
 * relabels itself to "High priority" after the edit, and the option rows are
 * translated copy, so a label-based selector would be locale-dependent for no
 * benefit.
 */
@OptIn(ExperimentalTestApi::class)
class SetPriorityFlowTest {

    /** Opens a seeded task's editor, which is where the attribute rows live. */
    private fun androidx.compose.ui.test.DesktopComposeUiTest.openEditor(title: String) {
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
    }

    @Test
    fun the_priority_row_is_addressable_by_tag() = runDesktopAppTest { koin ->
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = todayInSystemZone())
        openEditor("Buy milk")

        onNodeWithTag(TestTags.TASK_EDITOR_PRIORITY_ROW).assertIsDisplayed()
    }

    @Test
    fun choosing_high_updates_the_row_label() = runDesktopAppTest { koin ->
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = todayInSystemZone())
        openEditor("Buy milk")

        onNodeWithText("No priority").assertIsDisplayed()
        onNodeWithTag(TestTags.TASK_EDITOR_PRIORITY_ROW).performClick()
        onNodeWithTag(TestTags.PRIORITY_OPTION_HIGH).performClick()

        // The row relabels; the editor autosaves, so there is no save button here.
        onNodeWithText("High priority").assertIsDisplayed()
        onNodeWithText("No priority").assertDoesNotExist()
    }

    @Test
    fun every_priority_option_is_addressable() = runDesktopAppTest { koin ->
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = todayInSystemZone())
        openEditor("Buy milk")
        onNodeWithTag(TestTags.TASK_EDITOR_PRIORITY_ROW).performClick()

        // The sheet renders TaskPriority.entries, so all five need an id —
        // including Urgent, which the registry had no constant for until this MR.
        //
        // `assertExists`, not `assertIsDisplayed`: at the 768px test window the
        // sheet host clips the last row below the fold. The question here is
        // whether every option carries a tag, not whether the window is tall
        // enough to show it — the first test covers the on-screen claim.
        listOf(
            TestTags.PRIORITY_OPTION_NONE,
            TestTags.PRIORITY_OPTION_LOW,
            TestTags.PRIORITY_OPTION_MEDIUM,
            TestTags.PRIORITY_OPTION_HIGH,
            TestTags.PRIORITY_OPTION_URGENT,
        ).forEach { tag ->
            onNodeWithTag(tag).assertExists()
        }
    }
}
