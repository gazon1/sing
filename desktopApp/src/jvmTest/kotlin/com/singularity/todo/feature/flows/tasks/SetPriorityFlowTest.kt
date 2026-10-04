package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.Tag
import kotlin.test.Test

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
@Tag("slow")
class SetPriorityFlowTest {

    /** Opens a seeded task's editor, which is where the attribute rows live. */
    private fun androidx.compose.ui.test.DesktopComposeUiTest.openEditor(title: String) {
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
    }

    @Test
    fun the_priority_row_is_addressable_by_tag() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).given(due = todayInSystemZone())
        openEditor("Buy milk")

        assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
    }

    @Test
    fun choosing_high_updates_the_row_label() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).given(due = todayInSystemZone())
        openEditor("Buy milk")

        assertTextDisplayed("No priority")
        clickTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
        clickTag(TestTags.PRIORITY_OPTION_HIGH)

        // The row relabels; the editor autosaves, so there is no save button here.
        assertTextDisplayed("High priority")
        assertTextNotExists("No priority")
    }

    @Test
    fun every_priority_option_is_addressable() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).given(due = todayInSystemZone())
        openEditor("Buy milk")
        clickTag(TestTags.TASK_EDITOR_PRIORITY_ROW)

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
            assertTagExists(tag)
        }
    }
}
