package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Desktop coverage for the marking set on a task row.
 *
 * The set is computed by `computeTaskMarkings` and drawn by `TaskMarkingsRow`.
 * This file asserts what the row *says*, through the content descriptions the
 * icons carry — which is also what a screen reader gets, and the only part of
 * an icon a test can read.
 *
 * ## Two markings at once
 *
 * The point of replacing the single badge with a set is that a task can be
 * both blocked and overdue. Both marks are asserted on the same row, because
 * "the set is richer" is a claim about a row, not about a data class.
 *
 * ## Covered by other tiers
 *
 * `TaskMarkingPolicyTest` covers the computation; this file covers the
 * rendering and the production projection of the facts (the blocked edge is
 * written through the repository, so the arrangement of the join table is
 * exercised too).
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaBadgePolicyFlowTest {

    @Test
    @DisplayName("TASK-MARK-01 a task row shows every marking that applies")
    fun a_task_that_is_blocked_and_overdue_shows_both_markings() =
        runDesktopAppTest(clock = OVERDUE_CLOCK) { koin ->
            val repository = koin.get<TaskRepository>()
            val blocker = seed(koin, "blocker", "Blocking task", due = today)
            val blocked = seed(koin, "blocked", "Overdue blocked", due = yesterday)
            repository.setDependency(blocked, blocker, DependencyVerb.BLOCKS, enabled = true)
                .getOrThrow()

            tapTab("Today")
            awaitTag(TestTags.taskItem("Overdue blocked"))

            assertMarkingCount("Задача заблокирована зависимостями", expected = 1)
            assertMarkingCount("Просроченная задача", expected = 1)
        }

    @Test
    fun a_follow_up_shows_its_own_marking_and_not_the_blocked_one() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            val repository = koin.get<TaskRepository>()
            val goal = seed(koin, "goal", "Goal task", due = today)
            val follower = seed(koin, "follower", "Follower", due = today)
            repository.setDependency(follower, goal, DependencyVerb.FOLLOWS_UP, enabled = true)
                .getOrThrow()

            tapTab("Today")
            awaitTag(TestTags.taskItem("Follower"))

            assertMarkingCount("Задача продолжает другую", expected = 1)
            assertMarkingCount("Задача заблокирована зависимостями", expected = 0)
        }

    @Test
    fun an_in_progress_task_says_so() = runDesktopAppTest(clock = CLOCK) { koin ->
        seed(
            koin,
            "started",
            "Started task",
            due = today,
            workflowState = TaskWorkflowState.InProgress,
        )

        tapTab("Today")
        awaitTag(TestTags.taskItem("Started task"))

        assertMarkingCount("Задача в работе", expected = 1)
    }

    @Test
    fun a_pinned_task_says_so() = runDesktopAppTest(clock = CLOCK) { koin ->
        seed(koin, "pinned", "Pinned task", due = today, isPinned = true)

        tapTab("Today")
        awaitTag(TestTags.taskItem("Pinned task"))

        assertMarkingCount("Задача закреплена", expected = 1)
    }

    @Test
    fun an_undated_task_does_not_get_a_mark_saying_it_has_no_date() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, "undated", "Undated task", due = null)

            tapTab("Inbox")
            awaitTag(TestTags.taskItem("Undated task"))

            assertMarkingCount(
                "Задача без даты",
                expected = 0,
                because = "the absence of a date is not news, and a chip on every undated row is noise",
            )
        }

    @Test
    fun a_row_with_more_markings_than_fit_says_how_many_are_hidden() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            val repository = koin.get<TaskRepository>()
            val blocker = seed(koin, "hidden-blocker", "Hidden blocker", due = today)
            val goal = seed(koin, "hidden-goal", "Hidden goal", due = today)
            val busy = seed(
                koin,
                "busy",
                "Busy task",
                due = today,
                isPinned = true,
                workflowState = TaskWorkflowState.InProgress,
            )
            repository.setDependency(busy, blocker, DependencyVerb.BLOCKS, enabled = true)
                .getOrThrow()
            repository.setDependency(busy, goal, DependencyVerb.FOLLOWS_UP, enabled = true)
                .getOrThrow()

            tapTab("Today")
            awaitTag(TestTags.taskItem("Busy task"))

            // In progress, pinned, blocked and follow-up all qualify; the row
            // shows three and counts the fourth.
            assertTextDisplayed("+1")
        }

    // ── helpers ────────────────────────────────────────────────────────────

    private suspend fun seed(
        koin: Koin,
        id: String,
        title: String,
        due: LocalDate?,
        isPinned: Boolean = false,
        workflowState: TaskWorkflowState = TaskWorkflowState.Open,
    ): TaskId {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val task = testTask(
            id = TaskId(id),
            title = title,
            dueDate = due,
            userId = userId,
        ).copy(isPinned = isPinned, workflowState = workflowState)
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
        return task.id
    }

    /**
     * The row renders a bounded number of marks; this asserts how many carry a
     * given meaning.
     *
     * A count rather than a presence check: "this row is not blocked" is a
     * claim a presence check for the *other* marking cannot make.
     */
    private fun DesktopComposeUiTest.assertMarkingCount(
        description: String,
        expected: Int,
        because: String = "the row must state this fact exactly once",
    ) {
        waitForIdle()
        val actual = onAllNodesWithContentDescription(description).fetchSemanticsNodes().size
        assertEquals(expected, actual, "$description: $because")
    }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)

        /** "Overdue" is a comparison against *now*; shift the clock, not the text. */
        val OVERDUE_CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)

        /** The day before `today`, derived from the same instant the clock reports. */
        val yesterday: LocalDate = FIXED_NOW.toLocalDateTime(TimeZone.UTC).date
            .minus(1, DateTimeUnit.DAY)
    }
}
