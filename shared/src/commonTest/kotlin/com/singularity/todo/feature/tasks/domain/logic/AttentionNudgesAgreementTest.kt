package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Holds the attention list and the health counts to one answer about a task.
 *
 * The Health tab shows both: the list says which items want attention, the cards
 * say how many tasks are late, waiting or idle. They are two readings of the same
 * rows, and the list used to decide "late" and "waiting" with a copy of the rule
 * the counts are made with. Two copies of one rule are free to drift, and the
 * drift shows up as a tab calling a task late while the card beside it counts the
 * same task as fine — with nothing able to see it, because the two agreed on the
 * day they were written.
 *
 * So this test drives the two production entry points over one fixture and
 * asserts they agree: the list and the counts must never call one task two
 * things.
 *
 * ## The one place they differ
 *
 * They differ on purpose in one case, and the difference is the list's own order:
 * a task that is due today *and* waits on something is named by the list as due
 * soon, while the counts still call it waiting. That case is pinned by its own
 * test below, so it stays a decision rather than becoming a drift.
 */
@Tag("fast")
@Epic("Tasks")
@Feature("Domain logic")
class AttentionNudgesAgreementTest {
    private val today: LocalDate = LocalDate(2026, 10, 7)
    private val now: Instant = Instant.parse("2026-10-07T12:00:00Z")

    private fun task(
        id: String,
        due: LocalDate? = null,
        blockedBy: Set<TaskId> = emptySet(),
    ) = Task(
        id = TaskId(id),
        title = "task $id",
        dueDate = due,
        blockedBy = blockedBy,
        createdAt = now - 100.days,
        updatedAt = now,
        userId = UserId("u1"),
    )

    /** The counts the Health tab makes for [tasks]. */
    private fun counts(tasks: List<Task>): TaskHealthSummary =
        summarizeTaskHealthFromSelectors(
            allTasks = tasks,
            today = today,
            now = now,
            stagnationWindow = null,
            lastActivityOf = { it.updatedAt },
        )

    /**
     * The bucket the counts put [task] in.
     *
     * The counts are made over a whole list, so the bucket of one task is read by
     * counting that task and its prerequisites alone. A bucket does not depend on
     * the other tasks in the list, but the blocked set does — a prerequisite that
     * is not in the list is not waiting for anything.
     */
    private fun bucketInTheCounts(
        task: Task,
        prerequisites: List<Task>,
    ): TaskHealthBucket {
        val summary = counts(listOf(task) + prerequisites)
        return when {
            summary[TaskHealthBucket.OVERDUE] > 0 -> TaskHealthBucket.OVERDUE
            summary[TaskHealthBucket.BLOCKED] > 0 -> TaskHealthBucket.BLOCKED
            summary[TaskHealthBucket.STAGNANT] > 0 -> TaskHealthBucket.STAGNANT
            else -> TaskHealthBucket.FINE
        }
    }

    /** The prerequisites of [task] that are still standing in the way. */
    private fun prerequisitesOf(
        task: Task,
        tasks: List<Task>,
    ): List<Task> = tasks.filter { it.id in task.blockedBy }

    @Test
    fun `the list and the health counts call one task the same thing`() {
        val blocker = task("blocker")
        val late = task("late", due = today.minus(3, DateTimeUnit.DAY))
        val lateAndWaiting =
            task("late-and-waiting", due = today.minus(1, DateTimeUnit.DAY), blockedBy = setOf(blocker.id))
        val dueTodayAndWaiting = task("due-today-and-waiting", due = today, blockedBy = setOf(blocker.id))
        val waiting = task("waiting", blockedBy = setOf(blocker.id))
        val soon = task("soon", due = today.plus(1, DateTimeUnit.DAY))
        val fine = task("fine")
        val tasks = listOf(blocker, late, lateAndWaiting, dueTodayAndWaiting, waiting, soon, fine)

        val list = buildAttentionNudges(tasks, emptyList(), today, now)
        val summary = counts(tasks)

        assertEquals(2, summary[TaskHealthBucket.OVERDUE], "the counts find the two late tasks")
        assertEquals(
            2,
            list.count { it.signal == AttentionSignal.OVERDUE },
            "and the list names both of them",
        )

        list.filter { it.destination is AttentionDestination.Task }.forEach { nudge ->
            val named = (nudge.destination as AttentionDestination.Task).id
            val task = tasks.first { it.id == named }
            val bucket = bucketInTheCounts(task, prerequisitesOf(task, tasks))
            when (nudge.signal) {
                AttentionSignal.OVERDUE -> {
                    assertEquals(
                        TaskHealthBucket.OVERDUE,
                        bucket,
                        "the list calls it late, and so do the counts",
                    )
                }

                AttentionSignal.BLOCKED -> {
                    assertEquals(
                        TaskHealthBucket.BLOCKED,
                        bucket,
                        "the list calls it waiting, and so do the counts",
                    )
                }

                AttentionSignal.DUE_SOON -> {
                    assertNotEquals(
                        TaskHealthBucket.OVERDUE,
                        bucket,
                        "the list never calls a late task due soon",
                    )
                }

                AttentionSignal.PROJECT_DEADLINE -> {
                    Unit
                }
            }
        }
    }

    /**
     * The one case where the list and the counts answer differently.
     *
     * The counts classify the task as waiting, because waiting is what is wrong
     * with it. The list names the nearer deadline, because "what should I deal
     * with first" is a different question — and a list that answered it with
     * "waiting" would point at a task the user cannot start today.
     *
     * The difference is the list's order and not the rule: the state of the task
     * is still the classifier's answer, and it is the counts' answer too.
     */
    @Test
    fun `a task the counts call waiting is named due soon when it is due today`() {
        val blocker = task("blocker")
        val dueTodayAndWaiting = task("due-today-and-waiting", due = today, blockedBy = setOf(blocker.id))
        val tasks = listOf(blocker, dueTodayAndWaiting)

        val list = buildAttentionNudges(tasks, emptyList(), today, now)
        val summary = counts(tasks)

        assertEquals(1, summary[TaskHealthBucket.BLOCKED], "the counts call the task waiting")
        assertEquals(
            AttentionSignal.DUE_SOON,
            list.single().signal,
            "the list names the nearer deadline, which is the reason the user can act on today",
        )
    }
}
