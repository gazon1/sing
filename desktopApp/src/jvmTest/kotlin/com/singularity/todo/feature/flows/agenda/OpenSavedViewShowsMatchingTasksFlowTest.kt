package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
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
            // ── 1. Seed the matching task and confirm the agenda renders it ──────
            val t = tasks(koin)
            t.givenUndated(title = "Buy milk")

            tapTab("Inbox")
            assertTagDisplayed(TestTags.taskItem("Buy milk"))

            // ── 2. Seed a non-matching task ──────────────────────────────────────
            // Inbox holds undated tasks only. A dated task must not appear in a
            // saved Inbox view; without it, an unfiltered list would still pass
            // the positive checks.
            t.given(due = LocalDate(2026, 9, 17), title = "Dated task")

            // ── 3. Persist a saved view directly ─────────────────────────────────
            val definition = AgendaDefinition("My Active Tasks", AgendaPresets.Inbox.sections)
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

            // ── 4. Open Saved Views list ─────────────────────────────────────────
            awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
            assertTagDisplayed(TestTags.savedAgendaCard("My Active Tasks"))

            // ── 5. Tap the card → SavedAgendaResults screen ──────────────────────
            // The view name and matching content prove it rendered results; the
            // absent dated task proves it applied the stored filter, not an
            // unfiltered list.
            awaitTag(TestTags.savedAgendaCard("My Active Tasks")).performClick()
            assertNodeDisplayed(hasText("My Active Tasks"), "the saved view name on the results screen")
            assertTagDisplayed(TestTags.taskItem("Buy milk"))
            assertTagNotExists(TestTags.taskItem("Dated task"))
        }
}
