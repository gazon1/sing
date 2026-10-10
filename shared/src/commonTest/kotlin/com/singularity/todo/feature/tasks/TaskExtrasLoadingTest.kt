
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Verifies that [Task.tags] and [Task.dependsOn] are correctly populated
 * when loading tasks via [FakeTaskRepository.observeAll].
 *
 * In the production [com.singularity.todo.feature.tasks.data.TaskRepositoryImpl]
 * these fields are populated by [com.singularity.todo.core.database.userTasksWithExtras]
 * from the bundled [com.singularity.todo.core.database.TaskExtras] query.
 *
 * This test uses [FakeTaskRepository] which stores [Task] objects directly
 * (bypassing [com.singularity.todo.core.database.TaskEntity]), validating the
 * same end-to-end contract: seed with tags/deps → observeAll returns them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskExtrasLoadingTest {

    private val testUserId = UserId("test-user")
    private val repo = FakeTaskRepository()

    private fun makeTask(
        id: String,
        title: String,
        tags: List<TagId> = emptyList(),
        dependsOn: Set<TaskId> = emptySet(),
        completedAt: Instant? = null,
    ): Task {
        val now = Clock.System.now()
        return Task(
            id = TaskId.fromString(id),
            title = title,
            userId = testUserId,
            tags = tags,
            dependsOn = dependsOn,
            completedAt = completedAt,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `observeAll returns tasks with tags and dependsOn`() = runTest {
        val tag1 = TagId.fromString("tag-1")
        val tag2 = TagId.fromString("tag-2")
        val dep1 = TaskId.fromString("dep-1")

        repo.seed(
            makeTask("t1", "Task 1", tags = listOf(tag1, tag2), dependsOn = setOf(dep1)),
            makeTask("t2", "Task 2", tags = listOf(tag1)),
            makeTask("dep-1", "Dependency", tags = emptyList()),
        )

        val tasks = repo.observeAll().first()
        val byId = tasks.associateBy { it.id.value }

        assertEquals(listOf(tag1, tag2), byId["t1"]!!.tags)
        assertEquals(setOf(dep1), byId["t1"]!!.dependsOn)
        assertEquals(listOf(tag1), byId["t2"]!!.tags)
        assertEquals(emptyList<TagId>(), byId["dep-1"]!!.tags)
    }

    @Test
    fun `isBlocked returns true when dependency is not completed`() = runTest {
        val depId = TaskId.fromString("dep-unfinished")
        repo.seed(
            makeTask("t1", "Blocked task", dependsOn = setOf(depId)),
            makeTask("dep-unfinished", "Unfinished dependency"),
        )

        val tasks = repo.observeAll().first()
        val t1 = tasks.first { it.id.value == "t1" }
        val allTasks = tasks

        assertTrue(TaskComputed.isBlocked(t1, allTasks))
    }

    @Test
    fun `isBlocked returns false when dependency is completed`() = runTest {
        val depId = TaskId.fromString("dep-finished")
        repo.seed(
            makeTask("t1", "Blocked task", dependsOn = setOf(depId)),
            makeTask("dep-finished", "Finished dependency", completedAt = Clock.System.now()),
        )

        val tasks = repo.observeAll().first()
        val t1 = tasks.first { it.id.value == "t1" }
        val allTasks = tasks

        assertFalse(TaskComputed.isBlocked(t1, allTasks))
    }

    @Test
    fun `isBlocked returns false when task has no dependencies`() = runTest {
        repo.seed(makeTask("t1", "Free task"))

        val tasks = repo.observeAll().first()
        val t1 = tasks.first { it.id.value == "t1" }

        assertFalse(TaskComputed.isBlocked(t1, tasks))
    }
}
