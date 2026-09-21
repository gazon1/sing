package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for [FakeTaskRepository] with user-switch behavior.
 *
 * Verifies that the repository correctly re-subscribes to user-scoped data
 * when the current user changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImplTest {

    private val userA = UserId("user-a")
    private val userB = UserId("user-b")

    @Test
    fun observeAllForCurrentUser_re_subscribes_on_user_switch() = runTest {
        val authRepo = FakeAuthRepository(Session.Anonymous(userA))
        val currentUser = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)

        val taskA = makeTask(title = "Task for A", userId = userA)
        repo.seed(taskA)
        advanceUntilIdle()

        // take(1) auto-cancels after first emission so the subscription is not leaked
        val resultA = repo.observeAllForCurrentUser().take(1).first()
        assertEquals(1, resultA.size, "user-a should see their task")
        assertEquals("Task for A", resultA.first().title)

        authRepo.setUserId(userB)
        advanceUntilIdle()

        val resultB = repo.observeAllForCurrentUser().take(1).first()
        assertTrue(resultB.isEmpty(), "user-b should see no tasks")
    }

    @Test
    fun observeByFilter_re_subscribes_on_user_switch() = runTest {
        val authRepo = FakeAuthRepository(Session.Anonymous(userA))
        val currentUser = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)

        val taskA = makeTask(title = "Inbox A", userId = userA)
        repo.seed(taskA)
        advanceUntilIdle()

        val resultA = repo.observeByFilter(TaskFilter.Inbox).take(1).first()
        assertEquals(1, resultA.size, "user-a should see their inbox task")
        assertEquals("Inbox A", resultA.first().title)

        authRepo.setUserId(userB)
        advanceUntilIdle()

        val resultB = repo.observeByFilter(TaskFilter.Inbox).take(1).first()
        assertTrue(resultB.isEmpty(), "user-b should see no inbox tasks")
    }

    @Test
    fun create_and_observeForCurrentUser_round_trip() = runTest {
        val authRepo = FakeAuthRepository(Session.Anonymous(userA))
        val currentUser = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)

        val task = makeTask(title = "New Task", userId = userA)
        repo.create(task)
        advanceUntilIdle()

        // take(1) prevents the never-completing flatMapLatest subscription from leaking
        val observed = repo.observeForCurrentUser(task.id).take(1).first()
        assertNotNull(observed, "created task should be observable by id")
        assertEquals("New Task", observed.title)
    }

    private fun makeTask(
        id: TaskId = TaskId.generate(),
        title: String,
        userId: UserId,
    ): Task = Task(
        id = id,
        title = title,
        description = null,
        priority = TaskPriority.Medium,
        kind = TaskKind.Task,
        projectId = null,
        parentTaskId = null,
        dueDate = null,
        dueTime = null,
        completedAt = null,
        someday = false,
        archivedAt = null,
        isPinned = false,
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
        userId = userId,
        tags = emptyList(),
    )
}
