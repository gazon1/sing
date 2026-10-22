package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Unit tests for [ProjectTaskListDeriver].
 *
 * These rules used to be reachable only by constructing a `ProjectDetailViewModel`
 * with a coroutine scope and a set of fakes. They are pure functions of their
 * arguments, so they are asserted here directly and the ViewModel suite keeps
 * asserting the same behaviour through the state it emits.
 *
 * Tag: `@Tag("fast")` — no coroutines, no clock, no repository, so it can never
 * become the flaky kind of slow.
 */
@Tag("fast")
class ProjectTaskListDeriverTest {

    private val owner = UserId("test-user")
    private val project = ProjectId("p1")
    private val at = Instant.parse("2026-01-15T12:00:00Z")

    private fun task(
        id: String,
        projectId: ProjectId? = project,
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        archived: Boolean = false,
        blockedBy: Set<TaskId> = emptySet(),
        updatedAt: Instant = at,
    ): Task = Task(
        id = TaskId(id),
        title = "Task $id",
        projectId = projectId,
        dueDate = dueDate,
        completedAt = if (completed) at else null,
        archivedAt = if (archived) at else null,
        blockedBy = blockedBy,
        createdAt = at,
        updatedAt = updatedAt,
        userId = owner,
    )

    // ─── Filtering ──────────────────────────────────────────────────────────────

    @Test
    fun `nothing is hidden by default`() {
        val tasks = listOf(task("a"), task("b", completed = true))

        val visible = ProjectTaskListDeriver.visible(tasks, TaskVisibility(), emptySet())

        assertEquals(tasks, visible)
    }

    @Test
    fun `hideCompleted drops finished tasks and keeps the rest`() {
        val open = task("a")
        val done = task("b", completed = true)

        val visible = ProjectTaskListDeriver.visible(
            listOf(open, done),
            TaskVisibility(hideCompleted = true),
            emptySet(),
        )

        assertEquals(listOf(open), visible)
    }

    @Test
    fun `hideBlocked drops tasks with an unfinished dependency`() {
        val blocker = task("blocker")
        val blocked = task("blocked", blockedBy = setOf(TaskId("blocker")))
        val free = task("free")

        val tasks = listOf(blocker, blocked, free)
        val blockedIds = ProjectTaskListDeriver.blockedIds(tasks)

        val visible = ProjectTaskListDeriver.visible(
            tasks,
            TaskVisibility(hideBlocked = true),
            blockedIds,
        )

        assertEquals(setOf(TaskId("blocked")), blockedIds)
        assertEquals(listOf(blocker, free), visible)
    }

    /**
     * Completing a dependency unblocks its dependent — and hiding the completed
     * row does not change that.
     *
     * Worth pinning because the pairing looks like it should do the opposite: the
     * blocker vanishes from the visible list at the same moment the dependent
     * becomes eligible. Resolving `blockedIds` against the survivors rather than
     * the full list would make those two events indistinguishable.
     */
    @Test
    fun `completing a dependency unblocks its dependent, and hiding it changes nothing`() {
        val blocker = task("blocker", completed = true)
        val blocked = task("blocked", blockedBy = setOf(TaskId("blocker")))
        val tasks = listOf(blocker, blocked)

        assertEquals(
            emptySet(),
            ProjectTaskListDeriver.blockedIds(tasks),
            "a completed dependency does not block",
        )

        val visible = ProjectTaskListDeriver.visible(
            tasks,
            TaskVisibility(hideCompleted = true, hideBlocked = true),
            ProjectTaskListDeriver.blockedIds(tasks),
        )

        assertEquals(listOf(blocked), visible, "the dependent survives; the blocker is merely hidden")
    }

    // ─── Counts come from the unfiltered list ───────────────────────────────────

    /**
     * "5 of 23" must not become "5 of 5".
     *
     * The counts answer "how much of this project is done", which stays true when
     * the user hides the rows — so they are derived from the unfiltered list while
     * the preview is not.
     */
    @Test
    fun `counts ignore the filters`() {
        val tasks = (1..8).map { task("t$it", completed = it <= 3) }

        val visible = ProjectTaskListDeriver.visible(
            tasks,
            TaskVisibility(hideCompleted = true),
            emptySet(),
        )

        assertEquals(5, visible.size)
        assertEquals(8, ProjectTaskListDeriver.totalCount(tasks))
        assertEquals(3, ProjectTaskListDeriver.completedCount(tasks))
    }

    // ─── The preview cap ────────────────────────────────────────────────────────

    @Test
    fun `the preview is capped but the count is not`() {
        val tasks = (1..23).map { task("t$it") }

        val preview = ProjectTaskListDeriver.preview(tasks, TaskVisibility(), emptySet())

        assertEquals(ProjectTaskListDeriver.PREVIEW_LIMIT, preview.size)
        assertEquals(23, ProjectTaskListDeriver.totalCount(tasks))
    }

    @Test
    fun `the cap is applied after filtering, not before`() {
        val tasks = (1..8).map { task("t$it", completed = it > 5) }

        val preview = ProjectTaskListDeriver.preview(
            tasks,
            TaskVisibility(hideCompleted = true),
            emptySet(),
        )

        assertEquals(
            5,
            preview.size,
            "three completed rows are filtered out, so five open rows remain under the cap",
        )
    }

    @Test
    fun `a short list is returned whole`() {
        val tasks = listOf(task("a"), task("b"))

        assertEquals(tasks, ProjectTaskListDeriver.preview(tasks, TaskVisibility(), emptySet()))
    }

    // ─── Quick-add candidates ───────────────────────────────────────────────────

    @Test
    fun `quick-add offers other projects' open tasks only`() {
        val other = task("other", projectId = ProjectId("p2"))
        val inbox = task("inbox", projectId = null)
        val mine = task("mine")
        val doneElsewhere = task("done", projectId = ProjectId("p2"), completed = true)

        val candidates = ProjectTaskListDeriver.quickAddCandidates(
            listOf(other, inbox, mine, doneElsewhere),
            project,
        )

        assertEquals(listOf(TaskId("other")), candidates.map { it.id })
    }

    @Test
    fun `quick-add sorts by due date with undated last`() {
        val undated = task("undated")
        val late = task("late", dueDate = LocalDate(2026, 3, 1))
        val soon = task("soon", dueDate = LocalDate(2026, 1, 20))
        val all = listOf(ProjectId("p2"))

        val candidates = ProjectTaskListDeriver.quickAddCandidates(
            listOf(late, undated, soon).map { it.copy(projectId = all.first()) },
            project,
        )

        assertEquals(
            listOf(TaskId("soon"), TaskId("late"), TaskId("undated")),
            candidates.map { it.id },
        )
    }

    @Test
    fun `quick-add breaks a due-date tie by most recently updated`() {
        val older = task("older", dueDate = LocalDate(2026, 2, 1), updatedAt = at)
        val newer = task(
            "newer",
            dueDate = LocalDate(2026, 2, 1),
            updatedAt = Instant.parse("2026-01-16T12:00:00Z"),
        )

        val candidates = ProjectTaskListDeriver.quickAddCandidates(
            listOf(older, newer).map { it.copy(projectId = ProjectId("p2")) },
            project,
        )

        assertEquals(listOf(TaskId("newer"), TaskId("older")), candidates.map { it.id })
    }

    @Test
    fun `an archived dependency does not block`() {
        val trashed = task("blocker", archived = true)
        val blocked = task("blocked", blockedBy = setOf(TaskId("blocker")))

        assertEquals(
            emptySet(),
            ProjectTaskListDeriver.blockedIds(listOf(trashed, blocked)),
            "a trashed dependency cannot hold work up",
        )
    }
}
