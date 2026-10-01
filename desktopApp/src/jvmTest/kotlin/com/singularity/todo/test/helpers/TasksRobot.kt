package com.singularity.todo.test.helpers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.test.fakes.testTask
import kotlinx.datetime.LocalDate
import org.koin.core.Koin
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Fluent access to task preconditions and assertions, so a flow reads as
 * "given … when … then" instead of a sequence of helper calls with a Koin
 * reference threaded through each one.
 *
 * ```kotlin
 * runDesktopAppTest { koin ->
 *     tasks(koin)
 *         .given(due = today, title = "Buy milk")
 *         .assertInAgenda("Buy milk")
 * }
 * ```
 *
 * All optional parameters (projectId, tagIds, isPinned, priority, recurrence)
 * use neutral defaults so existing call sites compile unchanged.
 */
@OptIn(ExperimentalTestApi::class)
class TasksRobot(
    private val test: DesktopComposeUiTest,
    private val koin: Koin,
) {
    private var seq = 0
    private var seeded = 0

    /**
     * A task the agenda will render.
     *
     * All optional fields use neutral defaults (null / empty / false) so existing
     * call sites compile without changes.
     */
    suspend fun given(
        due: LocalDate,
        title: String = "Buy milk",
        completed: Boolean = false,
        projectId: ProjectId? = null,
        tagIds: List<TagId> = emptyList(),
        isPinned: Boolean = false,
        priority: TaskPriority = TaskPriority.None,
        recurrence: RecurrenceSpec? = null,
    ): TasksRobot = apply {
        seed(
            title = title,
            dueDate = due,
            completed = completed,
            projectId = projectId,
            tagIds = tagIds,
            isPinned = isPinned,
            priority = priority,
            recurrence = recurrence,
        )
        assertSeeded(koin, expected = ++seeded)
    }

    /**
     * An undated task, on purpose.
     *
     * Named differently from [given] so the choice is visible at the call site
     * and greppable: `grep givenUndated` lists every test that deliberately sits
     * on the broken path.
     */
    suspend fun givenUndated(title: String = "Buy milk"): TasksRobot = apply {
        seed(title = title, dueDate = null)
        assertSeeded(koin, expected = ++seeded)
    }

    /** Asserts the task is rendered in the agenda, waiting for the list to settle. */
    fun assertInAgenda(title: String): TasksRobot = apply {
        test.awaitTag(TestTags.taskItem(title)).assertIsDisplayed()
    }

    /** Waits for the task row and clicks it, returning the row for further work. */
    fun open(title: String): SemanticsNodeInteraction =
        test.awaitTag(TestTags.taskItem(title)).also { it.performClick() }

    private suspend fun seed(
        title: String,
        dueDate: LocalDate?,
        completed: Boolean = false,
        projectId: ProjectId? = null,
        tagIds: List<TagId> = emptyList(),
        isPinned: Boolean = false,
        priority: TaskPriority = TaskPriority.None,
        recurrence: RecurrenceSpec? = null,
    ) {
        val n = seq++
        val at = SEED_EPOCH + n.seconds
        val taskId = TaskId("robot-task-$n")
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val baseTask = testTask(
            id = taskId,
            title = title,
            dueDate = dueDate,
            completedAt = if (completed) at else null,
            projectId = projectId,
            tags = tagIds,
            isPinned = isPinned,
            priority = priority,
            userId = userId,
            createdAt = at,
            updatedAt = at,
        )
        val task = if (recurrence != null) {
            baseTask.copy(recurrence = recurrence)
        } else {
            baseTask
        }
        koin.get<TaskRepository>().upsert(task)
    }

    private companion object {
        val SEED_EPOCH = Instant.fromEpochMilliseconds(0)
    }
}

/** Entry point: `tasks(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.tasks(koin: Koin): TasksRobot =
    TasksRobot(this, koin)
