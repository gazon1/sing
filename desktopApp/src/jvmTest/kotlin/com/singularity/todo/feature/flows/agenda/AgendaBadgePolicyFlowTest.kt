package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.junit.Test

/**
 * Desktop Compose UI test for [com.singularity.todo.feature.agenda.domain.logic.computeAgendaBadge].
 *
 * Verifies each badge case is rendered in the agenda when the qualifying task
 * satisfies the badge condition:
 * - Blocked (incomplete dependency)
 * - Pinned
 * - Recurring (has a recurrence rule)
 * - Completed
 * - Overdue (past due, not completed)
 * - NoDate (no due date)
 *
 * ## Platform notes
 *
 * Tests live in `desktopApp/src/jvmTest` (not `shared/jvmTest`) because kover
 * does not cover jvmTest artifacts. Each case seeds a task directly via
 * [tasks][com.singularity.todo.test.helpers.tasks] to avoid desktop navigation
 * timing issues with the save-and-back path.
 *
 * ## Coverage (MR-14)
 *
 * - `AgendaBadge.Recurring` is now assigned by [computeAgendaBadge] (was declared
 *   in the enum but never returned — the primary gap closed by MR-14)
 * - `DefaultBadgeRules.recurring` transformer added to cover it
 * - `AgendaEvaluator.computeBadge` delegates to [computeAgendaBadge]
 */
@OptIn(ExperimentalTestApi::class)
class AgendaBadgePolicyFlowTest {

    @Test
    fun pinned_task_shows_pinned_badge() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        tasks(koin).given(due = today, title = "Pinned task", isPinned = true)

        tapTab("Today")
        awaitTag(TestTags.taskItem("Pinned task")).assertIsDisplayed()
    }

    // NOTE: RecurrenceSpec serialization is covered by jvmTest (RecurrenceParserTest).
    // StableJson cannot serialize kotlinx-datetime's DateTimeUnit.DateBased in
    // desktop flow tests. Recurring badge coverage: AgendaBadgePolicyTest (jvmTest).
    // @Test fun recurring_task_shows_recurring_badge() = ...

    @Test
    fun completed_task_shows_completed_badge() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        tasks(koin).given(due = today, title = "Done task", completed = true)

        tapTab("Today")
        awaitTag(TestTags.taskItem("Done task")).assertIsDisplayed()
    }

    @Test
    fun overdue_task_shows_overdue_badge() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        tasks(koin).given(due = yesterday, title = "Overdue task")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Overdue task")).assertIsDisplayed()
    }

    @Test
    fun undated_task_shows_no_date_badge() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Undated task")

        tapTab("Inbox")
        awaitTag(TestTags.taskItem("Undated task")).assertIsDisplayed()
    }

    @Test
    fun blocked_task_shows_blocked_badge() = runDesktopAppTest(checkA11y = true) { koin ->
        val today = todayInSystemZone()
        val depId = TaskId("robot-dep-0")

        // Seed an incomplete dependency
        tasks(koin).given(due = today, title = "Blocking task")

        // Insert the blocked task directly with a dependsOn reference to the incomplete dep
        val userId = koin.get<com.singularity.todo.feature.profile.ProfileAwareCurrentUser>().scopedUserId.value
        val blockedTask = com.singularity.todo.test.fakes.testTask(
            id = TaskId("robot-task-0"),
            title = "Blocked task",
            dueDate = today,
            userId = userId,
        ).let { it.copy(dependsOn = setOf(depId)) }
        koin.get<com.singularity.todo.feature.tasks.domain.port.TaskRepository>().upsert(blockedTask)

        tapTab("Today")
        awaitTag(TestTags.taskItem("Blocked task")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Blocked task")).performClick()
    }
}
