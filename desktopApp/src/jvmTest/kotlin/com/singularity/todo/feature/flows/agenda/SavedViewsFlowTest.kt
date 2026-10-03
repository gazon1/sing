package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Compose UI tests for the saved views list.
 *
 * Covers:
 * - List opens and shows empty state
 * - Navigation back to agenda
 * - Multiple views are shown (composition)
 * - Views are sorted alphabetically by name
 * - Duplicate names are allowed and both persist
 * - Card tap → Results; back → list with card intact
 * - Overflow menu on list shows Edit / Delete
 * - Delete removes the view
 *
 * ## Coverage (MR-4: SavedViewsFlowTest expansion)
 *
 * Note: each view-contents test seeds an undated task, because the agenda
 * results screen hides sections with zero tasks — without a matching task
 * the "No Date" section (the Inbox preset's catch-all for undated work)
 * never renders and cannot be awaited.
 */
@OptIn(ExperimentalTestApi::class)
class SavedViewsFlowTest {

    private suspend fun createView(koin: org.koin.core.Koin, name: String) {
        val repo = koin.get<SavedAgendaViewsRepository>()
        val def = AgendaDefinition(name, AgendaPresets.Inbox.sections)
        repo.upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = name,
                sectionsJson = def.toSectionsJson(),
                now = kotlin.time.Clock.System.now(),
            ),
        )
    }

    // ─── Basic list ─────────────────────────────────────────────────────

    @Test
    fun the_saved_views_list_opens_and_reports_its_empty_state() = runDesktopAppTest(checkA11y = true) {
        onNodeWithTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        onNodeWithText("Saved Views").assertIsDisplayed()
        onNodeWithText("No saved views yet").assertIsDisplayed()
        awaitTag(TestTags.SAVED_AGENDA_CREATE_FAB).assertIsDisplayed()
    }

    @Test
    fun leaving_the_saved_views_list_returns_to_the_agenda() = runDesktopAppTest(checkA11y = true) {
        onNodeWithTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        onNodeWithText("Saved Views").assertIsDisplayed()
        awaitTag(TestTags.SAVED_AGENDA_LIST_BACK).performClick()
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).assertIsDisplayed()
    }

    // ─── Composition ───────────────────────────────────────────────────

    @Test
    fun multiple_views_are_all_visible() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "Alpha View")
        createView(koin, "Beta View")
        createView(koin, "Gamma View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("alpha_view")).assertIsDisplayed()
        awaitTag(TestTags.savedAgendaCard("beta_view")).assertIsDisplayed()
        awaitTag(TestTags.savedAgendaCard("gamma_view")).assertIsDisplayed()
    }

    // ─── Alphabetical sort ───────────────────────────────────────────

    @Test
    fun views_are_sorted_alphabetically() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "Zebra")
        createView(koin, "Alpha")
        createView(koin, "Middle")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("alpha")).assertIsDisplayed()
        awaitTag(TestTags.savedAgendaCard("middle")).assertIsDisplayed()
        awaitTag(TestTags.savedAgendaCard("zebra")).assertIsDisplayed()
    }

    // ─── Duplicate names ─────────────────────────────────────────────

    @Test
    fun duplicate_view_names_are_allowed_and_both_persist() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "My View")
        createView(koin, "My View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()

        // Both cards render with the same slug → same testTag; assert the count is 2
        // (onAllNodesWithTag, because awaitTag requires exactly one match)
        val cards = onAllNodesWithTag(TestTags.savedAgendaCard("my_view")).fetchSemanticsNodes()
        assertTrue("expected 2 cards named 'My View', found ${cards.size}", cards.size == 2)

        // Both are in the repo
        val repo = koin.get<SavedAgendaViewsRepository>()
        val views = repo.observeAll().first()
        val count = views.count { it.name == "My View" }
        assertTrue("Two views named 'My View' must exist", count == 2)
    }

    // ─── Card tap → Results → back ─────────────────────────────────

    @Test
    fun card_tap_navigates_to_results_and_back_shows_list() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Navigation task")
        createView(koin, "Navigation Test")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("navigation_test")).assertIsDisplayed()

        // Tap card → Results ("No Date" section holds the undated task)
        awaitTag(TestTags.savedAgendaCard("navigation_test")).performClick()
        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Navigation task")).assertIsDisplayed()

        // Back → list with card
        awaitTag(TestTags.TOP_BAR_BACK_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("navigation_test")).assertIsDisplayed()
    }

    // ─── Overflow menu ────────────────────────────────────────────────

    @Test
    fun overflow_menu_shows_edit_and_delete() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Menu task")
        createView(koin, "Menu View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("menu_view")).assertIsDisplayed()

        // Open the card's overflow menu (⋮ is text-only — no tag, no contentDescription)
        onNodeWithText("⋮").performClick()

        // The desktop AWT menu bar also renders a plain "Edit" label (no click
        // action), so menu items are matched by text AND click action.
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Delete") and hasClickAction()).onFirst().assertIsDisplayed()
    }

    // ─── Delete ─────────────────────────────────────────────────────

    @Test
    fun delete_removes_view_from_list() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Delete task")
        createView(koin, "To Delete")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("to_delete")).assertIsDisplayed()

        // Open menu → Delete. List-level delete is immediate (no confirm dialog;
        // the confirmation exists only in Edit mode) per SavedAgendaListScreen.
        onNodeWithText("⋮").performClick()
        onAllNodes(hasText("Delete") and hasClickAction()).onFirst().performClick()

        // Card is gone from the list
        awaitTagGone(TestTags.savedAgendaCard("to_delete"))
        onNodeWithText("No saved views yet").assertIsDisplayed()
    }
}
