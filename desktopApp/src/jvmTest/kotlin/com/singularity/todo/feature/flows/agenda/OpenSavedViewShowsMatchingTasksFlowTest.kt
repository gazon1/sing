package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.core.ids.UserId
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
 * On Desktop this test seeds both the task and the saved view through the
 * repositories (the create-editor flow has its own dedicated test in
 * `SavedAgendaCreateFlowTest`), keeping this test focused on the card →
 * results navigation that MR-11 added.
 */
@OptIn(ExperimentalTestApi::class)
class OpenSavedViewShowsMatchingTasksFlowTest {

    @Test
    fun tapping_a_saved_view_card_opens_the_results_screen() = runDesktopAppTest(checkA11y = true) { koin ->
        // ── 1. Seed an active task (no due date) via repository ───────────────
        tasks(koin).givenUndated(title = "Buy milk")

        // ── 2. Persist a saved view directly ─────────────────────────────────
        // The Inbox preset contains the "No Date" section, which is what the
        // undated task below matches when the results screen evaluates it.
        val definition = AgendaDefinition("My Active Tasks", AgendaPresets.Inbox.sections)
        koin.get<SavedAgendaViewsRepository>().upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = "My Active Tasks",
                sectionsJson = definition.toSectionsJson(),
                now = kotlin.time.Clock.System.now(),
            ),
        )

        // ── 3. Open Saved Views list ─────────────────────────────────────────
        tapTab("Inbox")
        awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        // The list screen renders the seeded card — that is the on-screen proof
        // ("Saved Views" is the title text, not a testTag, so it is not awaited).
        awaitTag(TestTags.savedAgendaCard("My Active Tasks")).assertIsDisplayed()

        // ── 4. Tap the card → SavedAgendaResults screen (MR-11 new behaviour) ──
        // Inbox's "No Date" section matches the seeded "Buy milk" task.
        awaitTag(TestTags.savedAgendaCard("My Active Tasks")).performClick()
        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
    }
}
