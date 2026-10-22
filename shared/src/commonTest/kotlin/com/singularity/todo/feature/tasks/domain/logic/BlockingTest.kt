package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [activeBlockers] and [activeBlockersByTask].
 *
 * Pure domain tests — no database, no repository, no Compose.
 *
 * The rule under test: a prerequisite is in the way when it blocks the task, still
 * exists, is not finished, and has not been discarded. Everything else — a finished
 * prerequisite, a discarded one, one that no longer exists, and one that merely
 * follows up — is not in the way.
 */
@Tag("fast")
class BlockingTest {

    private val userId = UserId("test-user")
    private val epoch = kotlin.time.Instant.fromEpochMilliseconds(0)

    private fun makeTask(
        id: String,
        title: String = "Task $id",
        dependsOn: Set<TaskId> = emptySet(),
        completedAt: kotlin.time.Instant? = null,
        archivedAt: kotlin.time.Instant? = null,
    ): Task =
        Task(
            id = TaskId(id),
            title = title,
            kind = TaskKind.Task,
            priority = TaskPriority.None,
            dependsOn = dependsOn,
            completedAt = completedAt,
            createdAt = epoch,
            updatedAt = epoch,
            userId = userId,
            archivedAt = archivedAt,
        )

    // ─── The per-task form ───────────────────────────────────────────────────

    @Test
    fun `a task with no dependencies has no blockers`() {
        val task = makeTask("1")
        assertTrue(activeBlockers(task, listOf(task)).isEmpty())
    }

    @Test
    fun `an unfinished prerequisite is a blocker`() {
        val dep = makeTask("dep", "Dependency")
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")))
        assertEquals(listOf(dep), activeBlockers(task, listOf(task, dep)))
    }

    @Test
    fun `a finished prerequisite is not a blocker`() {
        val dep = makeTask("dep", "Dependency", completedAt = epoch)
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")))
        assertTrue(activeBlockers(task, listOf(task, dep)).isEmpty())
    }

    @Test
    fun `a discarded prerequisite is not a blocker`() {
        val dep = makeTask("dep", "Dependency", archivedAt = epoch)
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")))
        assertTrue(activeBlockers(task, listOf(task, dep)).isEmpty())
    }

    @Test
    fun `a prerequisite that no longer exists is not a blocker`() {
        val task = makeTask("1", dependsOn = setOf(TaskId("gone")))
        assertTrue(activeBlockers(task, listOf(task)).isEmpty())
    }

    @Test
    fun `only the unfinished prerequisites are blockers`() {
        val done = makeTask("done", "Done", completedAt = epoch)
        val open = makeTask("open", "Open")
        val task = makeTask("1", dependsOn = setOf(TaskId("done"), TaskId("open")))
        assertEquals(listOf(open), activeBlockers(task, listOf(task, done, open)))
    }

    // ─── The batch form ──────────────────────────────────────────────────────

    @Test
    fun `the batch form agrees with the per-task form`() {
        val done = makeTask("done", "Done", completedAt = epoch)
        val open = makeTask("open", "Open")
        val blocked = makeTask("blocked", "Blocked", dependsOn = setOf(TaskId("open")))
        val free = makeTask("free", "Free")
        val all = listOf(done, open, blocked, free)

        val byTask = activeBlockersByTask(all)

        assertEquals(listOf(open), byTask[TaskId("blocked")])
        assertFalse(byTask.containsKey(TaskId("free")))
        assertFalse(byTask.containsKey(TaskId("done")))
        assertFalse(byTask.containsKey(TaskId("open")))
    }

    @Test
    fun `the batch form is empty when nothing depends on anything`() {
        val all = listOf(makeTask("1"), makeTask("2"))
        assertTrue(activeBlockersByTask(all).isEmpty())
    }

    @Test
    fun `the batch form answers the same question the badge answers`() {
        val open = makeTask("open", "Open")
        val blocked = makeTask("blocked", "Blocked", dependsOn = setOf(TaskId("open")))
        val all = listOf(open, blocked)

        val byTask = activeBlockersByTask(all)
        all.forEach { task ->
            val blockedIds = TaskComputed.blockedIds(all)
            assertEquals(task.id in blockedIds, byTask.containsKey(task.id))
        }
    }
}
