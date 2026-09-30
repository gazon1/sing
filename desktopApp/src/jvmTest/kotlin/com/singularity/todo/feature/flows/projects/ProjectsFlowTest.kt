package com.singularity.todo.feature.flows.projects

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/projects/01-open-detail.yaml`.
 *
 * The Android flow taps `TestTags.TASKS_FAB` — the shell's extended FAB. The
 * desktop shell renders a plain `FloatingActionButton` with no testTag, only the
 * `Add project` contentDescription, so the selector differs while the flow is the
 * same: Plans → create → name → save → card → detail.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectsFlowTest {

    @Test
    fun plans_starts_empty() = runDesktopAppTest {
        tapTab("Plans")

        assertCurrentTab("Plans")
        onNodeWithText("No projects yet").assertIsDisplayed()
        onNodeWithContentDescription(DesktopShell.FAB_ADD_PROJECT).assertIsDisplayed()
    }

    @Test
    fun creating_a_project_puts_a_card_in_the_list() = runDesktopAppTest {
        tapTab("Plans")
        onNodeWithContentDescription(DesktopShell.FAB_ADD_PROJECT).performClick()

        onNodeWithTag(TestTags.PROJECT_EDITOR_NAME_INPUT)
            .performTextReplacement("Project Alpha")
        onNodeWithTag(TestTags.PROJECT_EDITOR_SAVE).performClick()

        // The card is tagged by slug, so this also proves the name round-tripped
        // through save and into the list.
        awaitTag(TestTags.projectCard("Project Alpha")).assertIsDisplayed()
    }

    @Test
    fun opening_a_project_reaches_its_detail_screen() = runDesktopAppTest {
        tapTab("Plans")
        onNodeWithContentDescription(DesktopShell.FAB_ADD_PROJECT).performClick()
        onNodeWithTag(TestTags.PROJECT_EDITOR_NAME_INPUT)
            .performTextReplacement("Project Alpha")
        onNodeWithTag(TestTags.PROJECT_EDITOR_SAVE).performClick()

        awaitTag(TestTags.projectCard("Project Alpha")).performClick()

        // The quick-add field only exists in the detail screen's Content state:
        // while the detail VM is Loading, the screen renders a LoadingIndicator
        // instead. Waiting for the tag is therefore waiting for the data, which
        // is what this assertion is actually about.
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()

        // The project *name* is deliberately not asserted: it matches both the
        // detail screen's top-bar title and the still-populated name field.
        // The card must be gone, but the outgoing screen stays composed until the
        // incoming one resolves, so this waits instead of checking once.
        awaitTagGone(TestTags.projectCard("Project Alpha"))
    }
}
