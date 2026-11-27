package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeTaskRepository
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers the read-modify-write in [UpdateTaskUseCase].
 *
 * Both tests drive the same window: one write is held open while a second starts, which is
 * what the task detail screen does whenever a debounced title write overlaps an entity edit.
 * The order is set by a gate, never by a sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
@Epic("Tasks")
@Feature("Use cases")
class UpdateTaskUseCaseTest {
    private val now: Instant = Instant.parse("2026-01-15T12:00:00Z")
    private val userId = UserId("test-user")

    private fun task() =
        Task(
            id = TaskId("t1"),
            title = "Original",
            priority = TaskPriority.None,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )

    @Test
    fun `two concurrent updates keep both fields`() =
        runTest {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var updateCalls = 0

            // The first write is held open after its read, which is exactly the window the
            // defect needs: the second caller reads the row the first one has not written yet.
            val repo =
                object : FakeTaskRepository() {
                    override suspend fun update(item: Task): Result<Task> {
                        val call = updateCalls++
                        if (call == 0) {
                            writeStarted.complete(Unit)
                            releaseWrite.await()
                        }
                        return super.update(item)
                    }
                }
            repo.seed(task())
            val useCase = UpdateTaskUseCase(repo, FakeClock(now))

            val settingPriority = launch { useCase(TaskId("t1")) { it.copy(priority = TaskPriority.High) } }
            writeStarted.await()

            val renaming = launch { useCase(TaskId("t1")) { it.copy(title = "Renamed") } }
            // Let the second caller reach the lock. Without one it reads and writes now.
            runCurrent()

            assertEquals(
                1,
                updateCalls,
                "the second write must wait for the first, not race it",
            )

            releaseWrite.complete(Unit)
            settingPriority.join()
            renaming.join()

            val stored = repo.tasks.value["t1"]
            assertEquals(TaskPriority.High, stored?.priority, "the first update must survive")
            assertEquals("Renamed", stored?.title, "the second update must survive")
        }

    @Test
    fun `a transform sees what the update before it wrote`() =
        runTest {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var updateCalls = 0

            val repo =
                object : FakeTaskRepository() {
                    override suspend fun update(item: Task): Result<Task> {
                        val call = updateCalls++
                        if (call == 0) {
                            writeStarted.complete(Unit)
                            releaseWrite.await()
                        }
                        return super.update(item)
                    }
                }
            repo.seed(task())
            val useCase = UpdateTaskUseCase(repo, FakeClock(now))

            val settingPriority = launch { useCase(TaskId("t1")) { it.copy(priority = TaskPriority.High) } }
            writeStarted.await()

            var sawPriority: TaskPriority? = null
            val renaming =
                launch {
                    useCase(TaskId("t1")) {
                        sawPriority = it.priority
                        it.copy(title = "Renamed")
                    }
                }
            runCurrent()
            releaseWrite.complete(Unit)
            settingPriority.join()
            renaming.join()

            assertTrue(
                sawPriority == TaskPriority.High,
                "the second read must see the first write, not the row it read before it",
            )
        }
}
