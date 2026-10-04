package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.coroutines.flow.first
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Desktop Compose UI test for editing an existing saved agenda view.
 *
 * Verifies:
 * - Tapping a saved view card opens Results mode (no edit chrome)
 * - Navigating back from Results shows the list with the card intact
 * - Overflow menu on the list card shows Edit and Delete
 * - Edit mode pre-fills the existing name
 * - Renaming and saving persists the change
 * - Deleting from Edit removes the view
 *
 * ## Coverage (MR-4)
 *
 * ## Navigation notes
 *
 * The "⋮" overflow lives on the **list card** (SavedAgendaCard), not on the
 * Results screen — Results renders a plain BackTopAppBar with no actions.
 * So the edit path is: list → overflow ⋮ → Edit (the card tap → Results →
 * back detour is not required).
 *
 * The results screen hides empty sections, so the card-tap test seeds an
 * undated task to make the Inbox preset's "No Date" section render.
 */
@OptIn(ExperimentalTestApi::class)
class SavedAgendaEditFlowTest {

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

    /**
     * Scrolls the edit-mode LazyColumn to its bottom so the Save/Delete buttons
     * (the last `item {}` in the list) are inside the visible window.
     *
     * Two nodes expose ScrollBy (the editor list and a background pane); the
     * first fetched is the editor LazyColumn (bounds (24,188)-(1000,744)).
     */
    @Test
    fun card_tap_opens_results_not_editor() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Results task")
        createView(koin, "My View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("my_view")).assertIsDisplayed()

        // Tapping the card opens Results — no name input should be visible
        awaitTag(TestTags.savedAgendaCard("my_view")).performClick()
        awaitTagGone(TestTags.SAVED_AGENDA_NAME_INPUT)
        // Results shows the agenda section for the view
        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()
    }

    @Test
    fun back_from_results_returns_to_list_with_card_intact() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Back task")
        createView(koin, "Back Test View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("back_test_view")).assertIsDisplayed()

        // Navigate to Results
        awaitTag(TestTags.savedAgendaCard("back_test_view")).performClick()
        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()

        // Go back — should return to list with card
        awaitTag(TestTags.TOP_BAR_BACK_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("back_test_view")).assertIsDisplayed()
    }

    @Test
    fun overflow_menu_edit_opens_editor_pre_filled() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "Editable View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("editable_view")).assertIsDisplayed()

        // Open overflow menu (⋮) on the card — text-only, no testTag.
        // The desktop AWT menu bar also renders a plain "Edit" label (no click
        // action), so menu items are matched by text AND click action.
        clickText("⋮")
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Delete") and hasClickAction()).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().performClick()

        // Edit mode: name input is shown pre-filled
        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).assertIsDisplayed()
        assertTextDisplayed("Editable View")
    }

    @Test
    fun renaming_and_saving_persists_the_new_name() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "Old Name")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("old_name")).assertIsDisplayed()

        // Open menu → Edit
        clickText("⋮")
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().performClick()

        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).performTextClearance()
        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).performTextInput("New Name")

        // The action row is pinned below the scroll area, so Save is reachable
        // without scrolling even with the Inbox preset's 8 sections. This line is
        // the regression test: the row used to be the last LazyColumn item, and on
        // a 1024x768 window the primary action of the form sat below the fold —
        // `performClick()` on the off-screen node silently injected a click at
        // out-of-window coordinates and the test then failed 5s later on an
        // unrelated await. `assertIsDisplayed` before the click is what pins it.
        awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).assertIsDisplayed()

        awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).performClick()

        // Save navigates back to list
        awaitTagGone(TestTags.SAVED_AGENDA_NAME_INPUT)

        // Verify the new name is in the repo
        val repo = koin.get<SavedAgendaViewsRepository>()
        val names = repo.observeAll().first().map { it.name }
        assertTrue("New Name" in names, "renamed view must be persisted")
        assertTrue("Old Name" !in names, "old name must not remain")
    }

    @Test
    fun delete_from_edit_removes_view() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin, "To Delete")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("to_delete")).assertIsDisplayed()

        // Open menu → Edit
        clickText("⋮")
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().performClick()

        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).assertIsDisplayed()

        // No scroll needed: the action row is pinned below the list. The
        // assertIsDisplayed below is the regression check — the row used to be
        // the last item of the scrolling column.
        // Delete button exists in edit mode
        awaitTag(TestTags.SAVED_AGENDA_DELETE_BUTTON).assertIsDisplayed()
        awaitTag(TestTags.SAVED_AGENDA_DELETE_BUTTON).performClick()

        // ConfirmActionDialog confirm button (tagged dialog_confirm, label "Delete")
        awaitTag(TestTags.Dialog.CONFIRM).assertIsDisplayed()
        awaitTag(TestTags.Dialog.CONFIRM).performClick()

        // Should navigate back to list and show empty state
        awaitTagGone(TestTags.SAVED_AGENDA_NAME_INPUT)
        assertTextDisplayed("No saved views yet")
    }
}
