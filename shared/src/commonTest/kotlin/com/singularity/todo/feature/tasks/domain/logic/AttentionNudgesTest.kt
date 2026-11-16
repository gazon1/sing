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

    @Test
    fun `the same list twice gives the same order`() {
        val blocker = task("blocker")
        val tasks =
            listOf(
                blocker,
                task("a", due = today),
                task("b", blockedBy = setOf(blocker.id)),
                task("c", due = today.minus(3, DateTimeUnit.DAY)),
            )

        assertEquals(nudges(tasks).map { it.key }, nudges(tasks).map { it.key })
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
