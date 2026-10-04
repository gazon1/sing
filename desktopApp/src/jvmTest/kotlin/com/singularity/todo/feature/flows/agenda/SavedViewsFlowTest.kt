package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickTag
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
    fun the_saved_views_list_opens_and_reports_its_empty_state() = runDesktopAppTest(checkA11y = true) {
        clickTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON)

        assertTextDisplayed("Saved Views")
        assertTextDisplayed("No saved views yet")
        assertTagDisplayed(TestTags.SAVED_AGENDA_CREATE_FAB)
    }

    @Test
    fun leaving_the_saved_views_list_returns_to_the_agenda() = runDesktopAppTest(checkA11y = true) {
        clickTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON)
        assertTextDisplayed("Saved Views")

        // The list's own top-bar arrow, not the shell's — the shell control is a
        // hamburger only at a tab root, and this screen is pushed.
        clickTag(TestTags.SAVED_AGENDA_LIST_BACK)

        assertTagDisplayed(TestTags.AGENDA_SAVED_VIEWS_BUTTON)
    }
}
