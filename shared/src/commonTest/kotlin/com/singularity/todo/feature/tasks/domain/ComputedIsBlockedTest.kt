package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TaskComputed.isBlocked].
 *
 * These are pure domain tests — no database, no repository, no Compose.
 */
class ComputedIsBlockedTest {

    private val userId = UserId("test-user")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun makeTask(
        id: String,
        title: String = "Task $id",
        dependsOn: Set<TaskId> = emptySet(),
        completedAt: Instant? = null,
        archivedAt: Instant? = null, // null = not trashed
    ): Task = Task(
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

    // ─── No dependencies ────────────────────────────────────────────────────

    @Test
    fun `task with no dependencies is never blocked`() {
        val task = makeTask("1")
        val allTasks = listOf(task)
        assertFalse(TaskComputed.isBlocked(task, allTasks))
    }

    // ─── Active dependency ───────────────────────────────────────────────────

    @Test
    fun `task is blocked when a dependency is incomplete`() {
        val dep = makeTask("dep", "Dependency")
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")))
        assertTrue(TaskComputed.isBlocked(task, listOf(task, dep)))
    }

    @Test
    fun `task is not blocked when all dependencies are completed`() {
        val dep = makeTask("dep", "Dependency", completedAt = epoch)
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")))
        assertFalse(TaskComputed.isBlocked(task, listOf(task, dep)))
    }

    // ─── Trashed dependency ─────────────────────────────────────────────────

    @Test
    fun `trashed dependency is not a blocker`() {
        val dep = makeTask("dep", "Dependency", archivedAt = epoch)
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")))
        assertFalse(TaskComputed.isBlocked(task, listOf(task, dep)))
    }

    // ─── Multiple dependencies ───────────────────────────────────────────────

    @Test
    fun `task is blocked when any dependency is incomplete`() {
        val dep1 = makeTask("dep1", "Dep 1", completedAt = epoch)
        val dep2 = makeTask("dep2", "Dep 2") // incomplete
        val task = makeTask("1", dependsOn = setOf(TaskId("dep1"), TaskId("dep2")))
        assertTrue(TaskComputed.isBlocked(task, listOf(task, dep1, dep2)))
    }

    @Test
    fun `task is not blocked when all multiple dependencies are complete`() {
        val dep1 = makeTask("dep1", "Dep 1", completedAt = epoch)
        val dep2 = makeTask("dep2", "Dep 2", completedAt = epoch)
        val task = makeTask("1", dependsOn = setOf(TaskId("dep1"), TaskId("dep2")))
        assertFalse(TaskComputed.isBlocked(task, listOf(task, dep1, dep2)))
    }

    // ─── Missing dependency ─────────────────────────────────────────────────

    @Test
    fun `task is not blocked when dependency ID is not in allTasks (unknown dep not a blocker)`() {
        // An unknown dep ID is absent from allTasks, so dep = null, and the any{} returns false.
        // This is a data-integrity issue (stale dep ID) but isBlocked = false by design.
        val task = makeTask("1", dependsOn = setOf(TaskId("unknown-dep")))
        assertFalse(TaskComputed.isBlocked(task, listOf(task)))
    }

    // ─── Self-dependency ────────────────────────────────────────────────────

    @Test
    fun `self-dependency blocks the task`() {
        val task = makeTask("1", dependsOn = setOf(TaskId("1")))
        assertTrue(TaskComputed.isBlocked(task, listOf(task)))
    }

    // ─── Completed dependent task ───────────────────────────────────────────

    @Test
    fun `completed task with incomplete dependencies is still blocked (dep status determines block, not dependent)`() {
        // isBlocked checks if the *dependent's* deps are incomplete, regardless of the
        // dependent's own completion state. The dep here is incomplete → blocked.
        val dep = makeTask("dep", "Dependency") // incomplete
        val task = makeTask("1", dependsOn = setOf(TaskId("dep")), completedAt = epoch)
        assertTrue(TaskComputed.isBlocked(task, listOf(task, dep)))
    }
}
