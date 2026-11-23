package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop carrier for `AGENDA-04` — a saved view is a stored filter, not a
 * stored label.
 *
 * The retired Maestro flow `Maestro/flows/agenda/04-saved-view-results.yaml`
 * (#3121) drove the same behaviour through the Android shell (overflow
 * save-current, real IME, popup resource ids). Those device steps are already
 * covered by `03-saved-views-crud`; the behaviour below is what the flow
 * existed to prove.
 *
 * The view is seeded through the repository rather than the editor, so the test
 * stays focused on the card → results navigation. Save-current has its own owner
 * in `SavedAgendaCreateFlowTest`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class OpenSavedViewShowsMatchingTasksFlowTest {
    @Test
    @DisplayName("AGENDA-04 opening a saved view lists the tasks matching its definition")
    fun tapping_a_saved_view_card_opens_the_results_screen() =
        runDesktopAppTest(checkA11y = true) { koin ->
            // ── 1. Seed an active task (no due date) via repository ───────────────
            tasks(koin).givenUndated(title = "Buy milk")

            // ── 2. Persist a saved view directly ─────────────────────────────────
            // The All preset contains the "No Date" section, which is what the
            // undated task below matches when the results screen evaluates it.
            val definition = AgendaDefinition("My Active Tasks", AgendaPresets.All.sections)
            koin.get<SavedAgendaViewsRepository>().upsert(
                SavedAgendaViewFactory.create(
                    userId = UserId.anonymous,
                    name = "My Active Tasks",
                    sectionsJson = definition.toSectionsJson(),
                    now =
                        kotlin.time.Clock.System
                            .now(),
                ),
            )

            // ── 3. Open Saved Views list ─────────────────────────────────────────
            tapTab("Inbox")
            assertTagDisplayed(TestTags.taskItem("Buy milk"))
            awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
            // The list screen renders the seeded card — that is the on-screen proof
            // ("Saved Views" is the title text, not a testTag, so it is not awaited).
            assertTagDisplayed(TestTags.savedAgendaCard("My Active Tasks"))

            // ── 4. Tap the card → SavedAgendaResults screen ──────────────────────
            // Inbox's "No Date" section matches the seeded "Buy milk" task. The
            // view name and the matching content together prove this is a stored
            // filter rendered as results, not a stored label over an unfiltered list.
            awaitTag(TestTags.savedAgendaCard("My Active Tasks")).performClick()
            // The view name appears in the shell bar and in the results heading; the
            // assertion only needs proof the name is visible on the results screen.
            onAllNodes(hasText("My Active Tasks")).onFirst().assertIsDisplayed()
            assertTagDisplayed(TestTags.agendaSection("No Date"))
            assertTagDisplayed(TestTags.taskItem("Buy milk"))
        }
}
