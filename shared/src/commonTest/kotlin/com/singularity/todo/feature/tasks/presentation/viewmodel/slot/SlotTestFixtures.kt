package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiDeps
import com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCollaborationDeps
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskSchedulingDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.BlockedTasksException
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskReopenUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/** Fixed instant: slot VMs resolve due dates against "today". */
private val SLOT_NOW: Instant = Instant.parse("2026-01-15T12:00:00Z")

// `TEST_TZ` moved to `com.singularity.todo.test.fakes` (CommonFakes.kt) when the
// clock became injectable in a second place; a zone declared per test file is a
// second thing to keep in sync with the clock it belongs to.

internal val TEST_USER = UserId("test-user")

/**
 * Records reminder scheduling so a test can assert that an alarm was (or was not) set.
 *
 * The platform scheduler is a real side effect, so the fake tracks the calls instead of
 * performing them — which is also what makes the reminders slot's "row then alarm" order
 * assertable.
 *
 * [isSupported] is a constructor parameter, not a constant, because a test for the
 * capability gate needs a scheduler that says no. Asserting the gate against a fake that
 * always says yes would pass whether or not the gate exists.
 */
internal class RecordingReminderScheduler(override val isSupported: Boolean = true) : ReminderScheduler {
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
internal class SlotFakes(
    /** Whether the platform scheduler claims it can arm alarms; false reproduces desktop. */
    remindersSupported: Boolean = true,
) {
    val scheduler = RecordingReminderScheduler(isSupported = remindersSupported)

    val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(Session.Anonymous(TEST_USER)),
    )
    val taskRepo = FakeTaskRepository(explicitCurrentUser = currentUser)
    val projectsRepo = FakeProjectsRepository(currentUser = currentUser)
    val tagsRepo = FakeTagsRepository(currentUser = currentUser)
    val checklistRepo = FakeChecklistRepository()
    val reminderRepo = FakeReminderRepository(currentUser = currentUser)
    val attachmentsRepo = FakeAttachmentRepository(currentUser = currentUser)
    val notesRepo = FakeNotesRepository(currentUser = currentUser)
    val timeTrackingRepo = FakeTimeTrackingRepository(FakeClock(SLOT_NOW))

    fun deps(): TaskDetailDeps = TaskDetailDeps(
        core = core(),
        children = children(),
        scheduling = scheduling(),
        collaboration = collaboration(),
        ai = ai(),
        context = context(),
    )

    /**
     * The bundle accessors exist so a slot test can build exactly the group under test
     * instead of the whole aggregate — the same narrowing production gets from the
     * grouped constructor.
     */

    fun core() = TaskCoreDeps(
        taskRepo = taskRepo,
        updateTask = UpdateTaskUseCase(taskRepo, FakeClock(SLOT_NOW)),
        createTask = CreateTaskUseCase(taskRepo, FakeClock(SLOT_NOW), currentUser),
        completeRecurring = stubCompleteRecurring,
        completion = TaskReopenUseCase(taskRepo, projectsRepo, FakeClock(SLOT_NOW)),
        updateSeries = UpdateTaskSeriesUseCase(taskRepo),
    )

    fun children() = TaskChildrenDeps(
        checklistRepository = checklistRepo,
        attachmentsRepo = attachmentsRepo,
        projectsRepo = projectsRepo,
        tagsRepo = tagsRepo,
    )

    fun scheduling() = TaskSchedulingDeps(
        reminderRepo = reminderRepo,
        reminderScheduler = scheduler,
        timeZoneProvider = TEST_TZ,
    )

    fun collaboration() = TaskCollaborationDeps(
        notesRepo = notesRepo,
        timeTrackingRepo = timeTrackingRepo,
        currentUser = currentUser,
    )

    fun ai() = TaskAiDeps()

    fun context() = TaskContextDeps(clock = FakeClock(SLOT_NOW), debounceMs = 300L)

    /**
     * The recurring completion the slot asked for — the call, not the write.
     *
     * A recurring close goes through the use case rather than through a write of
     * `completedAt`, and this is where that is asserted.
     */
    var recurringCalled: TaskId? = null

    /**
     * The recurring completion that was actually written.
     *
     * The call and the write are two things: a refusal is a call that wrote
     * nothing, and a test that cannot tell them apart cannot assert that
     * nothing was written before the user's answer.
     */
    var recurringCompleted: TaskId? = null

    /**
     * A stand-in for the real use case that mirrors its refusal.
     *
     * The real use case refuses in its write while a prerequisite is open, and
     * the slot asks the user when the write says no. A stub that only recorded
     * the call would answer "no" to nothing, and the slot would have nothing
     * to ask about — so the stub refuses the same way the real use case does,
     * and records the write only when it is allowed to happen.
     */
    private val stubCompleteRecurring = object : CompleteRecurringTaskUseCase(
        repo = taskRepo,
        unitOfWork = FakeUnitOfWork(),
        clock = FakeClock(SLOT_NOW),
        timeZoneProvider = TEST_TZ,
        calculator = RecurrenceCalculator,
    ) {
        override suspend fun invoke(taskId: TaskId, force: Boolean): Result<Task> {
            recurringCalled = taskId
            val task = taskRepo.get(taskId)
                ?: return Result.failure(IllegalStateException("Stub — no such task: $taskId"))
            if (!force && Blocking.activeBlockers(task, taskRepo.tasks.value.values.toList()).isNotEmpty()) {
                return Result.failure(BlockedTasksException(setOf(taskId)))
            }
            recurringCompleted = taskId
            return Result.success(task)
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
    createdAt = SLOT_NOW,
    updatedAt = SLOT_NOW,
)

/** Slot scopes run on the test dispatcher; collectors still need a real-time pump. */
internal fun testSlotScope(scope: CoroutineScope): AutoCloseableCoroutineScope =
    AutoCloseableCoroutineScope(scope.coroutineContext)

/** A tag for the catalogue the entity slot filters against. */
internal fun tag(id: com.singularity.todo.feature.tags.TagId, name: String) = com.singularity.todo.feature.tags.Tag(
    id = id,
    name = name,
    color = 0xFF00FF00.toInt(),
    createdAt = SLOT_NOW,
    updatedAt = SLOT_NOW,
    userId = TEST_USER,
)
