package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The attention list: what wants the user's attention, and in what order.
 *
 * ## What these tests protect
 *
 * The list is a shortlist, so two properties matter and neither is visible from
 * the outside without an assertion:
 *
 * - **One item, one reason.** A task that is late *and* waiting must appear once,
 *   under the more relevant reason. A list that named both would be longer and
 *   no more useful.
 * - **The order is stable.** The list answers "what first?", so an order that
 *   changed between two openings of the same screen would make it untrustworthy.
 *
 * The exclusions matter just as much: a nudge for work the user deliberately
 * parked, or for a project whose work is finished, is the app arguing with the
 * user.
 */
@Tag("fast")
@Epic("Tasks")
@Feature("Domain logic")
class AttentionNudgesTest {
    private val today: LocalDate = LocalDate(2026, 10, 7)
    private val now: Instant = Instant.parse("2026-10-07T12:00:00Z")

    private fun task(
        id: String,
        due: LocalDate? = null,
        blockedBy: Set<TaskId> = emptySet(),
        completed: Instant? = null,
        trashed: Instant? = null,
        someday: Boolean = false,
        projectId: ProjectId? = null,
    ) = Task(
        id = TaskId(id),
        title = "task $id",
        dueDate = due,
        blockedBy = blockedBy,
        completedAt = completed,
        archivedAt = trashed,
        someday = someday,
        projectId = projectId,
        createdAt = now - 100.days,
        updatedAt = now,
        userId = UserId("u1"),
    )

    private fun project(
        id: String,
        due: LocalDate? = null,
        closed: Instant? = null,
        deleted: Boolean = false,
    ) = Project(
        id = ProjectId(id),
        name = "project $id",
        color = 0,
        dueDate = due,
        closedAt = closed,
        isDeleted = deleted,
        createdAt = now - 100.days,
        updatedAt = now,
        userId = UserId("u1"),
    )

    private fun nudges(
        tasks: List<Task>,
        projects: List<Project> = emptyList(),
    ) = buildAttentionNudges(tasks, projects, today, now)

    // ── The signals ────────────────────────────────────────────────────────────

    @Test
    fun `a task past its due date is named as overdue`() {
        val list = nudges(listOf(task("t1", due = today.minus(1, DateTimeUnit.DAY))))

        assertEquals(1, list.size)
        assertEquals(AttentionSignal.OVERDUE, list.first().signal)
        assertEquals("Overdue since yesterday", list.first().reason)
    }

    /**
     * A date further off is written the way the app writes dates.
     *
     * An ISO date in a reason line reads like a log entry; "Oct 2" reads like
     * something said to a person.
     */
    @Test
    fun `a date further off is written the way the app writes dates`() {
        val list = nudges(listOf(task("t1", due = today.minus(5, DateTimeUnit.DAY))))

        assertEquals("Overdue since Oct 2", list.first().reason)
    }

    @Test
    fun `a task due today or tomorrow is named as due soon`() {
        val list =
            nudges(
                listOf(
                    task("t1", due = today),
                    task("t2", due = today.plus(1, DateTimeUnit.DAY)),
                    task("t3", due = today.plus(2, DateTimeUnit.DAY)),
                ),
            )

        assertEquals(listOf("task:t1", "task:t2"), list.map { it.key })
        assertTrue(list.all { it.signal == AttentionSignal.DUE_SOON })
        assertEquals("Due today", list.first().reason)
    }

    @Test
    fun `a task waiting on an unfinished prerequisite is named as blocked`() {
        val blocker = task("blocker")
        val list = nudges(listOf(blocker, task("t1", blockedBy = setOf(blocker.id))))

        assertEquals(1, list.size)
        assertEquals(AttentionSignal.BLOCKED, list.first().signal)
    }

    @Test
    fun `a task whose prerequisite is finished is not named`() {
        val blocker = task("blocker", completed = now - 2.days)
        val list = nudges(listOf(blocker, task("t1", blockedBy = setOf(blocker.id))))

        assertTrue(list.isEmpty(), "a finished prerequisite does not make the task wait: $list")
    }

    /**
     * Due soon outranks blocked.
     *
     * The two do not overlap in the app's own buckets — a task due today is in the
     * Today section, and the health classifier puts overdue before blocked — but a
     * task can be due today *and* wait on something. The shortlist has to pick
     * one, and the nearer deadline is the one the user can act on today.
     */
    @Test
    fun `a task due today that waits on something is named once, as due soon`() {
        val blocker = task("blocker")
        val list = nudges(listOf(blocker, task("t1", due = today, blockedBy = setOf(blocker.id))))

        assertEquals(1, list.size)
        assertEquals(AttentionSignal.DUE_SOON, list.first().signal)
    }

    @Test
    fun `a task past its due date that waits on something is named once, as overdue`() {
        val blocker = task("blocker")
        val list =
            nudges(
                listOf(
                    blocker,
                    task("t1", due = today.minus(1, DateTimeUnit.DAY), blockedBy = setOf(blocker.id)),
                ),
            )

        assertEquals(1, list.size)
        assertEquals(AttentionSignal.OVERDUE, list.first().signal)
    }

    // ── The exclusions ─────────────────────────────────────────────────────────

    @Test
    fun `finished, discarded and deferred work is not named`() {
        val list =
            nudges(
                listOf(
                    task("done", due = today.minus(1, DateTimeUnit.DAY), completed = now),
                    task("gone", due = today.minus(1, DateTimeUnit.DAY), trashed = now),
                    task("later", due = today, someday = true),
                ),
            )

        assertTrue(list.isEmpty(), "work that is not to be done is not on the list: $list")
    }

    @Test
    fun `work in a closed project is not named`() {
        val closed = project("p1", closed = now - 5.days)
        val list =
            nudges(
                tasks = listOf(task("t1", due = today.minus(1, DateTimeUnit.DAY), projectId = closed.id)),
                projects = listOf(closed),
            )

        assertTrue(list.isEmpty(), "a closed project's work is finished work: $list")
    }

    // ── The project deadline ───────────────────────────────────────────────────

    @Test
    fun `a project whose deadline has passed and has unfinished work is named`() {
        val project = project("p1", due = today.minus(1, DateTimeUnit.DAY))
        val list =
            nudges(
                tasks = listOf(task("t1", projectId = project.id)),
                projects = listOf(project),
            )

        assertEquals(1, list.size)
        assertEquals(AttentionSignal.PROJECT_DEADLINE, list.first().signal)
        assertEquals(AttentionDestination.Project(project.id), list.first().destination)
        assertEquals("Its deadline has passed with 1 unfinished task", list.first().reason)
    }

    @Test
    fun `a project whose deadline has passed with all its work finished is not named`() {
        val project = project("p1", due = today.minus(1, DateTimeUnit.DAY))
        val list =
            nudges(
                tasks = listOf(task("t1", projectId = project.id, completed = now)),
                projects = listOf(project),
            )

        assertTrue(list.isEmpty(), "a met deadline is not a problem: $list")
    }

    /**
     * A deadline that is today has not passed yet.
     *
     * The reason said "its deadline has passed" for a deadline that arrived
     * today — the app accusing the user of being late on the day the date
     * arrived.
     */
    @Test
    fun `a project whose deadline is today says so`() {
        val project = project("p1", due = today)
        val list =
            nudges(
                tasks = listOf(task("t1", projectId = project.id)),
                projects = listOf(project),
            )

        assertEquals(1, list.size)
        assertEquals("Its deadline is today with 1 unfinished task", list.first().reason)
    }

    @Test
    fun `a project whose deadline has not arrived is not named`() {
        val project = project("p1", due = today.plus(1, DateTimeUnit.DAY))
        val list =
            nudges(
                tasks = listOf(task("t1", projectId = project.id)),
                projects = listOf(project),
            )

        assertTrue(list.isEmpty(), "a deadline that has not arrived is not yet a problem: $list")
    }

    /**
     * A deferred task still means the project is unfinished.
     *
     * The task is parked, not done — so the promise the deadline made about the
     * body of work is still unmet.
     */
    @Test
    fun `a project with only deferred work left is still named`() {
        val project = project("p1", due = today.minus(1, DateTimeUnit.DAY))
        val list =
            nudges(
                tasks = listOf(task("t1", projectId = project.id, someday = true)),
                projects = listOf(project),
            )

        assertEquals(1, list.size)
        assertEquals(AttentionSignal.PROJECT_DEADLINE, list.first().signal)
        assertEquals(
            "Its deadline has passed with 1 unfinished task",
            list.first().reason,
            "a deferred task is unfinished work, and the count says so",
        )
    }

    // ── The order ──────────────────────────────────────────────────────────────

    @Test
    fun `the most relevant kind comes first`() {
        val blocker = task("blocker")
        val project = project("p1", due = today.minus(1, DateTimeUnit.DAY))
        val list =
            nudges(
                tasks =
                    listOf(
                        blocker,
                        task("blocked", blockedBy = setOf(blocker.id)),
                        task("soon", due = today),
                        task("late", due = today.minus(1, DateTimeUnit.DAY)),
                        task("mine", projectId = project.id),
                    ),
                projects = listOf(project),
            )

        assertEquals(
            listOf(
                AttentionSignal.OVERDUE,
                AttentionSignal.DUE_SOON,
                AttentionSignal.BLOCKED,
                AttentionSignal.PROJECT_DEADLINE,
            ),
            list.map { it.signal },
        )
    }

    @Test
    fun `items of the same kind are ordered by the date that makes them due`() {
        val list =
            nudges(
                listOf(
                    task("later", due = today.minus(1, DateTimeUnit.DAY)),
                    task("earlier", due = today.minus(5, DateTimeUnit.DAY)),
                ),
            )

        assertEquals(listOf("task:earlier", "task:later"), list.map { it.key })
    }

    /**
     * Two items of the same kind and the same date are told apart by their own
     * identity.
     *
     * The tie-break is what makes the order a fact about the list rather than a
     * fact about how the rows happened to arrive.
     */
    @Test
    fun `items of the same kind and the same date are ordered by their identity`() {
        val list =
            nudges(
                listOf(
                    task("b", due = today),
                    task("a", due = today),
                ),
            )

        assertEquals(listOf("task:a", "task:b"), list.map { it.key })
    }

    /**
     * An item without a date goes after the items that have one.
     *
     * Kotlin's own comparison puts a null first, which would have put a
     * waiting task with no due date ahead of a task that is late — the list
     * would have said "this first" about an item with no date to be early by.
     */
    @Test
    fun `an item without a date goes after the items that have one`() {
        val blocker = task("blocker")
        val list =
            nudges(
                listOf(
                    blocker,
                    task("waiting", blockedBy = setOf(blocker.id)),
                    task("late", due = today.minus(1, DateTimeUnit.DAY)),
                ),
            )

        assertEquals(listOf("task:late", "task:waiting"), list.map { it.key })
    }

    /**
     * Within one kind, an item without a date goes after the ones that have one.
     *
     * The test above cannot carry that rule on its own: it compares two
     * different kinds, and the kind already decides the order. The rule only
     * shows itself between two items of the *same* kind — and the waiting kind
     * is the one that can carry a dateless item, because a waiting task keeps
     * its due date when it has one.
     *
     * So: two waiting tasks, one dated and one not. The dated one is first.
     */
    @Test
    fun `within one kind an item without a date goes last`() {
        val blocker = task("blocker")
        val list =
            nudges(
                listOf(
                    blocker,
                    task("waiting-undated", blockedBy = setOf(blocker.id)),
                    task("waiting-dated", due = today.plus(5, DateTimeUnit.DAY), blockedBy = setOf(blocker.id)),
                ),
            )

        assertEquals(listOf("task:waiting-dated", "task:waiting-undated"), list.map { it.key })
    }

    /**
     * The kind still comes first: an item without a date of a more relevant
     * kind outranks a dated item of a less relevant kind.
     */
    @Test
    fun `the kind outranks the date even when one item has no date`() {
        val blocker = task("blocker")
        val project = project("p1", due = today.minus(1, DateTimeUnit.DAY))
        val list =
            nudges(
                tasks =
                    listOf(
                        blocker,
                        task("waiting", blockedBy = setOf(blocker.id)),
                        task("mine", projectId = project.id),
                    ),
                projects = listOf(project),
            )

        assertEquals(
            listOf(AttentionSignal.BLOCKED, AttentionSignal.PROJECT_DEADLINE),
            list.map { it.signal },
        )
    }

    // ── The key ────────────────────────────────────────────────────────────────

    @Test
    fun `the key identifies the item`() {
        val list = nudges(listOf(task("t1", due = today)))

        assertEquals("task:t1", list.first().key)
        assertEquals(AttentionDestination.Task(TaskId("t1")), list.first().destination)
    }

    @Test
    fun `an empty task list names nothing`() {
        assertTrue(nudges(emptyList()).isEmpty())
        assertNull(nudges(emptyList()).firstOrNull())
    }
}
