package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.TIMEOUT_MS
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitCheckboxChecked
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickCheckbox
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop mirror of `Maestro/flows/tasks/toggle-task-complete.yaml` and the
 * row-rendering half of `agenda/01-smart-lists.yaml`.
 *
 * Fixtures are seeded through the repository rather than created by the flow, so
 * a failure here localises to the row interaction rather than to the editor's
 * save path — which is its own flow ([CreateTaskFlowTest]).
 *
 * ## Why every fixture here carries a due date
 *
 * Not an accident, and no longer a limitation. An *undated* fixture is the one
 * thing the Inbox and Today presets disagree on, which made it the canary for a bug
 * that is now fixed and separately regression-tested: every NavEntry resolved the
 * same ViewModelStoreOwner, so the tabs shared one `AgendaViewModel` and none of them
 * evaluated its own definition. Asserting on due-today fixtures alone could never have
 * caught it — every preset defines a "Today" section, so a stale ViewModel still
 * passes. See [AgendaTabDefinitionFlowTest] for the undated and tomorrow-dated cases,
 * which only a correctly-scoped ViewModel satisfies.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TaskRowFlowTest {

    @Test
    fun a_task_due_today_is_listed_under_todays_section() = runDesktopAppTest(checkA11y = true) { koin ->
        assertCurrentTab("Today")

        tasks(koin)
            .given(due = todayInSystemZone(), title = "Buy milk")
            .assertInAgenda("Buy milk")

        awaitTag(TestTags.agendaSection("Today")).assertIsDisplayed()
    }

    @Test
    fun a_task_due_today_is_also_in_the_inbox_agenda() = runDesktopAppTest(checkA11y = true) { koin ->
        tapTab("Inbox")

        tasks(koin)
            .given(due = todayInSystemZone(), title = "Buy milk")
            .assertInAgenda("Buy milk")
    }

    @Test
    fun toggling_the_checkbox_reports_the_task_completed() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).given(due = todayInSystemZone())

        awaitTag(TestTags.taskCheckbox("Buy milk")).assertIsDisplayed()
        clickCheckbox(TestTags.taskCheckbox("Buy milk"))

        // Asserting the toggled semantics makes this a state assertion rather than
        // a click-counting smoke test.
        awaitCheckboxChecked(TestTags.taskCheckbox("Buy milk"))
    }
}
