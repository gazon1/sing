package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/agenda/04-saved-view-results.yaml`.
 *
 * Verifies that tapping a saved view card in the Saved Views list navigates to
 * the results screen showing tasks that match the view's definition.
 *
 * ## What this tests (MR-11新增)
 *
 * - `SavedAgendaScreen` renders `AgendaScreen(definition)` when in View mode
 * - `AgendaNavigator.openSavedAgendaResults` pushes `SavedAgendaResults` route
 * - `onViewSelected` in `SavedAgendaListScreen` opens results, not edit
 *
 * ## Platform-specific notes
 *
 * The Maestro flow (`04-saved-view-results.yaml`) tests the full end-to-end:
 * create task → save agenda → open saved views → tap card → see results.
 * On Desktop, the save-and-navigate-away transition from the saved-agenda editor
 * has a timing issue with `tapTab` (the drawer fails to open after dismissing
 * the discard guard). This test uses [tasks] to seed data via the repository
 * directly, avoiding the navigation path that triggers the timing issue.
 */
@OptIn(ExperimentalTestApi::class)
class OpenSavedViewShowsMatchingTasksFlowTest {

    @Test
    fun tapping_a_saved_view_card_opens_the_results_screen() = runDesktopAppTest(checkA11y = true) { koin ->
        // ── 1. Seed an active task (no due date) via repository ───────────────
        // This bypasses the desktop navigation timing issue with save-and-tab.
        tasks(koin).givenUndated(title = "Buy milk")

        // ── 2. Navigate to Inbox and save the agenda as a named view ──────────
        tapTab("Inbox")
        awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
        awaitTag(TestTags.AGENDA_SAVE_CURRENT_BUTTON).performClick()
        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).assertIsDisplayed()
        onNodeWithTag(TestTags.SAVED_AGENDA_NAME_INPUT)
            .performTextReplacement("My Active Tasks")
        onNodeWithTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).performClick()
        // Save succeeds; the editor stays open (no auto-pop on save).
        // Leave via top bar back — draft is dirty → discard guard.
        awaitTag(TestTags.TOP_BAR_BACK_BUTTON).performClick()
        awaitTag("Discard changes?").assertIsDisplayed()
        onNodeWithTag(TestTags.Dialog.DISMISS).performClick()
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).assertIsDisplayed()

        // ── 3. Open Saved Views list ─────────────────────────────────────────
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag("Saved Views").assertIsDisplayed()
        awaitTag(TestTags.savedAgendaCard("My Active Tasks")).assertIsDisplayed()

        // ── 4. Tap the card → SavedAgendaResults screen (MR-11 new behaviour) ──
        // Inbox's "No Date" section matches the seeded "Buy milk" task.
        awaitTag(TestTags.savedAgendaCard("My Active Tasks")).performClick()
        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
    }
}
