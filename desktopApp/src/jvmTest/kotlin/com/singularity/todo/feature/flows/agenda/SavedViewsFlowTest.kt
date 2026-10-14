package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.helpers.assertNodeDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

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
@Tag("slow")
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
        clickTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON)
        assertTextDisplayed("Saved Views")
        assertTextDisplayed("No saved views yet")
        assertTagDisplayed(TestTags.SAVED_AGENDA_CREATE_FAB)
    }

    @Test
    fun leaving_the_saved_views_list_returns_to_the_agenda() = runDesktopAppTest(checkA11y = true) {
        clickTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON)
        assertTextDisplayed("Saved Views")
        awaitTag(TestTags.SAVED_AGENDA_LIST_BACK).performClick()
        assertTagDisplayed(TestTags.AGENDA_SAVED_VIEWS_BUTTON)
    }

    // ─── Composition ───────────────────────────────────────────────────

    @Test
    fun multiple_views_are_all_visible() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "Alpha View")
        createView(koin, "Beta View")
        createView(koin, "Gamma View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        assertTagDisplayed(TestTags.savedAgendaCard("alpha_view"))
        assertTagDisplayed(TestTags.savedAgendaCard("beta_view"))
        assertTagDisplayed(TestTags.savedAgendaCard("gamma_view"))
    }

    // ─── Alphabetical sort ───────────────────────────────────────────

    @Test
    fun views_are_sorted_alphabetically() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "Zebra")
        createView(koin, "Alpha")
        createView(koin, "Middle")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        assertTagDisplayed(TestTags.savedAgendaCard("alpha"))
        assertTagDisplayed(TestTags.savedAgendaCard("middle"))
        assertTagDisplayed(TestTags.savedAgendaCard("zebra"))
    }

    // ─── Duplicate names ─────────────────────────────────────────────

    @Test
    fun duplicate_view_names_are_allowed_and_both_persist() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "My View")
        createView(koin, "My View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()

        // Both cards render with the same slug → same testTag; assert the count is 2
        // (countNodes, because awaitTag requires exactly one match)
        val cards = countNodes(TestTags.savedAgendaCard("my_view"))
        assertTrue(cards == 2, "expected 2 cards named 'My View', found $cards")

        // Both are in the repo
        val repo = koin.get<SavedAgendaViewsRepository>()
        val views = repo.observeAll().first()
        val count = views.count { it.name == "My View" }
        assertTrue(count == 2, "Two views named 'My View' must exist")
    }

    // ─── Card tap → Results → back ─────────────────────────────────

    @Test
    fun card_tap_navigates_to_results_and_back_shows_list() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Navigation task")
        createView(koin, "Navigation Test")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        assertTagDisplayed(TestTags.savedAgendaCard("navigation_test"))

        // Tap card → Results ("No Date" section holds the undated task)
        awaitTag(TestTags.savedAgendaCard("navigation_test")).performClick()
        assertTagDisplayed(TestTags.agendaSection("No Date"))
        assertTagDisplayed(TestTags.taskItem("Navigation task"))

        // Back → list with card
        awaitTag(TestTags.TOP_BAR_BACK_BUTTON).performClick()
        assertTagDisplayed(TestTags.savedAgendaCard("navigation_test"))
    }

    // ─── Overflow menu ────────────────────────────────────────────────

    @Test
    fun overflow_menu_shows_edit_and_delete() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Menu task")
        createView(koin, "Menu View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        assertTagDisplayed(TestTags.savedAgendaCard("menu_view"))

        // Open the card's overflow menu (⋮ is text-only — no tag, no contentDescription)
        clickText("⋮")

        // The desktop AWT menu bar also renders a plain "Edit" label (no click
        // action), so menu items are matched by text AND click action.
        assertNodeDisplayed(hasText("Edit") and hasClickAction(), "Edit action")
        assertNodeDisplayed(hasText("Delete") and hasClickAction(), "Delete action")
    }

    // ─── Delete ─────────────────────────────────────────────────────

    @Test
    fun delete_removes_view_from_list() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Delete task")
        createView(koin, "To Delete")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        assertTagDisplayed(TestTags.savedAgendaCard("to_delete"))

        // Open menu → Delete → confirm. Confirmation is required for hard-delete
        // (SavedAgendaView has no soft-delete/restore), per delete-affordances spec.
        clickText("⋮")
        onAllNodes(hasText("Delete") and hasClickAction()).onFirst().performClick()
        awaitTag(TestTags.Dialog.CONFIRM).performClick()

        // Card is gone from the list
        awaitTagGone(TestTags.savedAgendaCard("to_delete"))
        assertTextDisplayed("No saved views yet")
    }
}
