package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/agenda/02-saved-views-open.yaml`.
 *
 * The saved-views list is platform-neutral — it is reached from the agenda's own
 * top-bar bookmark, not from either platform's navigation chrome — so this flow
 * carries over unchanged apart from the harness.
 */
@OptIn(ExperimentalTestApi::class)
class SavedViewsFlowTest {

    @Test
    fun the_saved_views_list_opens_and_reports_its_empty_state() = runDesktopAppTest {
        onNodeWithTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()

        onNodeWithText("Saved Views").assertIsDisplayed()
        onNodeWithText("No saved views yet").assertIsDisplayed()
        onNodeWithTag(TestTags.SAVED_AGENDA_CREATE_FAB).assertIsDisplayed()
    }

    @Test
    fun leaving_the_saved_views_list_returns_to_the_agenda() = runDesktopAppTest {
        onNodeWithTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        onNodeWithText("Saved Views").assertIsDisplayed()

        // The list's own top-bar arrow, not the shell's — the shell control is a
        // hamburger only at a tab root, and this screen is pushed.
        onNodeWithTag(TestTags.SAVED_AGENDA_LIST_BACK).performClick()

        onNodeWithTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).assertIsDisplayed()
    }
}
