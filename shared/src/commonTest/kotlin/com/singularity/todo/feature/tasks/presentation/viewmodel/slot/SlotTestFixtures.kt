@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

internal val TEST_TZ: TimeZoneProvider = object : TimeZoneProvider {
    override fun current() = kotlinx.datetime.TimeZone.UTC
}

internal val TEST_USER = UserId("test-user")

/**
 * Records reminder scheduling so a test can assert that an alarm was (or was not) set.
 *
 * The platform scheduler is a real side effect, so the fake tracks the calls instead of
 * performing them — which is also what makes the reminders slot's "row then alarm" order
 * assertable.
 */
internal class RecordingReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Reminder>()
    val cancelledIds = mutableListOf<ReminderId>()
    val cancelledTasks = mutableListOf<TaskId>()

    override suspend fun schedule(reminder: Reminder) {
        scheduled += reminder
    }

    override suspend fun cancel(id: ReminderId, userId: UserId) {
        cancelledIds += id
    }

    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
        cancelledTasks += taskId
    }
}

/**
 * Fakes shared by every slot test.
 *
 * The fakes take their default `FakeProfileAwareCurrentUser`, which runs on
 * `Dispatchers.Unconfined` — an eager dispatcher that drains inline, so the
 * `scopedUserId` emission lands before the slot subscribes. Slot tests therefore
 * settle with `runCurrent()` / `advanceTimeBy(...)` and use no real time.
 *
 * This supersedes R1 in `docs/decisions/2026-09-27-mr1-retro-findings.md` and R6 in
 * `2026-09-28-mr2-retro-findings.md`, which both recorded that slot tests had to pump with
 * real `delay()`. That was true while the default was `Dispatchers.Default`; commit
 * `560f3bf8` changed it to `Unconfined`, and the constraint was never re-tested. Verified:
 * all 45 slot tests run on virtual time.
 */
internal class SlotFakes {
    val scheduler = RecordingReminderScheduler()

    val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(Session.Anonymous(TEST_USER)),
    )
    val taskRepo = FakeTaskRepository(explicitCurrentUser = currentUser)
    val projectsRepo = FakeProjectsRepository(currentUser = currentUser)
    val tagsRepo = FakeTagsRepository(currentUser = currentUser)
    val checklistRepo = FakeChecklistRepository()
    val reminderRepo = FakeReminderRepository(currentUser = currentUser)
    val attachmentsRepo = FakeAttachmentRepository(currentUser = currentUser)

    fun deps(): TaskDetailDeps = TaskDetailDeps(
        taskRepo = taskRepo,
        updateTask = UpdateTaskUseCase(taskRepo, Clock.System),
        createTask = CreateTaskUseCase(taskRepo, Clock.System, currentUser),
        projectsRepo = projectsRepo,
        tagsRepo = tagsRepo,
        checklistRepository = checklistRepo,
        reminderRepo = reminderRepo,
        reminderScheduler = scheduler,
        attachmentsRepo = attachmentsRepo,
        timeZoneProvider = TEST_TZ,
        clock = Clock.System,
        completeRecurring = stubCompleteRecurring,
        debounceMs = 300L,
    )

    /** Records the recurring-completion call so the completion slot can be asserted on. */
    var recurringCompleted: TaskId? = null

    private val stubCompleteRecurring = object : CompleteRecurringTaskUseCase(
        repo = taskRepo,
        clock = Clock.System,
        timeZoneProvider = TEST_TZ,
        calculator = RecurrenceCalculator,
    ) {
        override suspend fun invoke(taskId: TaskId): Result<Task> {
            recurringCompleted = taskId
            return Result.failure(IllegalStateException("Stub — not implemented in slot tests"))
        }
    }
}

/** A task flow a slot can be driven from, standing in for the coordinator's subscription. */
internal class TaskSource(initial: Task? = null) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Task?> = _state.asStateFlow()

    fun emit(task: Task?) {
        _state.value = task
    }
}

internal fun task(id: String = "t1", title: String = "Test task"): Task = Task(
    id = TaskId(id),
    title = title,
    userId = TEST_USER,
    createdAt = Clock.System.now(),
    updatedAt = Clock.System.now(),
)

/** Slot scopes run on the test dispatcher; collectors still need a real-time pump. */
internal fun testSlotScope(scope: CoroutineScope): AutoCloseableCoroutineScope =
    AutoCloseableCoroutineScope(scope.coroutineContext)

/** A tag for the catalogue the entity slot filters against. */
internal fun tag(id: com.singularity.todo.feature.tags.TagId, name: String) = com.singularity.todo.feature.tags.Tag(
    id = id,
    name = name,
    color = 0xFF00FF00.toInt(),
    createdAt = Clock.System.now(),
    updatedAt = Clock.System.now(),
    userId = TEST_USER,
)
