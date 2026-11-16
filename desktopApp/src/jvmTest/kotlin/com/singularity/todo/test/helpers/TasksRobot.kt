package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
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
class TasksRobot(private val test: DesktopComposeUiTest, private val koin: Koin) {
    private var seq = 0
    private var seeded = 0

    /**
     * The id of the task that was seeded last.
     *
     * A test that addresses the task by id — a proposal is addressed to a task, not to
     * a title — needs to know which id the seed gave it.
     */
    var lastTaskId: TaskId? = null
        private set

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
        createdAt: Instant? = null,
        /**
         * When the task was finished, for a test that reads the completion date.
         *
         * Only meaningful with [completed]. Without it a completed task is finished at
         * the seed epoch, which is fine for "is it completed" and useless for "what
         * date does the screen say".
         */
        completedAt: Instant? = null,
        /** An extra field on the seeded task; see [seed]. */
        overrides: Task.() -> Unit = {},
    ): TasksRobot =
        apply {
            seed(
                title = title,
                dueDate = due,
                completed = completed,
                projectId = projectId,
                tagIds = tagIds,
                isPinned = isPinned,
                priority = priority,
                recurrence = recurrence,
                createdAt = createdAt,
                completedAt = completedAt,
                overrides = overrides,
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
    suspend fun givenUndated(title: String = "Buy milk"): TasksRobot =
        apply {
            seed(title = title, dueDate = null)
            assertSeeded(koin, expected = ++seeded)
        }

    /**
     * An undated task last touched at [updatedAt].
     *
     * [givenUndated] seeds at the epoch, which is decades past any staleness
     * threshold — fine for "is this stale at all", useless for "is this stale under
     * *this* threshold". A test about the setting needs the task at a known age.
     */
    suspend fun givenUndatedAt(
        title: String = "Buy milk",
        updatedAt: Instant,
    ): TasksRobot =
        apply {
            // The seed stamps `updatedAt` from `createdAt`, so setting the creation
            // moment is how the last touch is placed.
            seed(title = title, dueDate = null, createdAt = updatedAt)
            assertSeeded(koin, expected = ++seeded)
        }

    /** Asserts the task is rendered in the agenda, waiting for the list to settle. */
    fun assertInAgenda(title: String): TasksRobot =
        apply {
            test.assertTagDisplayed(TestTags.taskItem(title))
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
        createdAt: Instant? = null,
        completedAt: Instant? = null,
        /**
         * An extra field on the seeded task.
         *
         * The factory's named parameters cover what the agenda reads; a field the
         * screen must *show* but the list does not sort by — the start date — is
         * set through this block rather than by widening the factory for one test.
         */
        overrides: Task.() -> Unit = {},
    ) {
        val n = seq++
        // The seeded task is as old as the epoch unless a test says otherwise. A test
        // that asks for the age of a task — the first-run offer is drawn for a task
        // that is minutes old — passes the moment the task was created instead.
        val at = createdAt ?: (SEED_EPOCH + n.seconds)
        val taskId = TaskId("robot-task-$n")
        lastTaskId = taskId
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val baseTask =
            testTask(
                id = taskId,
                title = title,
                dueDate = dueDate,
                completedAt =
                    when {
                        completedAt != null -> completedAt
                        completed -> at
                        else -> null
                    },
                projectId = projectId,
                tags = tagIds,
                isPinned = isPinned,
                priority = priority,
                userId = userId,
                createdAt = at,
                updatedAt = at,
                overrides = overrides,
            )
        val task =
            if (recurrence != null) {
                baseTask.copy(recurrence = recurrence)
            } else {
                baseTask
            }
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
    }

    private companion object {
        val SEED_EPOCH = Instant.fromEpochMilliseconds(0)
    }
}

/** Entry point: `tasks(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.tasks(koin: Koin): TasksRobot = TasksRobot(this, koin)
