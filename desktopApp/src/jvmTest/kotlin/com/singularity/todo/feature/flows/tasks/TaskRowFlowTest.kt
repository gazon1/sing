package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.seedTask
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/tasks/toggle-task-complete.yaml` and the
 * row-rendering half of `agenda/01-smart-lists.yaml`.
 *
 * Fixtures are seeded through the repository rather than created by the flow, so
 * a failure here localises to the row interaction rather than to the editor's
 * save path — which is its own flow ([CreateTaskFlowTest]).
 *
 * ## Open question: undated tasks do not render
 *
 * Every fixture here carries a due date, and that is deliberate. Seeding an
 * *undated* task and reading it back gives
 * `TaskRepository.observeAll() == [task]` while the agenda renders "No tasks",
 * and the task stays invisible across a full tab switch that recreates the
 * ViewModel. The same gap makes the create flow's assertion fail: the editor does
 * create the task, it just lands in a bucket this build does not surface.
 *
 * The Android suite asserts the opposite — `agenda/01-smart-lists.yaml` waits for
 * `"No Date  ·  1"` on the Inbox tab and is documented as green — so either the
 * desktop path differs or the gap is platform-independent and the Android flow
 * was written against a build that has since changed. Resolving that is a product
 * question rather than a harness one; until it is answered these flows pin the
 * behaviour that is actually observable.
 */
@OptIn(ExperimentalTestApi::class)
class TaskRowFlowTest {

    @Test
    fun a_task_due_today_is_listed_under_todays_section() = runDesktopAppTest { koin ->
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = todayInSystemZone())

        assertCurrentTab("Today")

        awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
        awaitTag(TestTags.agendaSection("Today")).assertIsDisplayed()
    }

    @Test
    fun a_task_due_today_is_also_in_the_inbox_agenda() = runDesktopAppTest { koin ->
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = todayInSystemZone())

        tapTab("Inbox")

        awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
    }

    @Test
    fun toggling_the_checkbox_reports_the_task_completed() = runDesktopAppTest { koin ->
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = todayInSystemZone())

        awaitTag(TestTags.taskCheckbox("Buy milk")).assertIsDisplayed()
        onNodeWithTag(TestTags.taskCheckbox("Buy milk")).performClick()

        // Asserting the toggled semantics makes this a state assertion rather than
        // a click-counting smoke test.
        waitUntil(
            conditionDescription = "the checkbox reports checked",
            timeoutMillis = 5_000,
        ) {
            onAllNodesWithTag(TestTags.taskCheckbox("Buy milk"))
                .fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.ToggleableState] == ToggleableState.On }
        }
    }
}
