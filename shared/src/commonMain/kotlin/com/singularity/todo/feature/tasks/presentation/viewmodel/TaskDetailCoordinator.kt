package com.singularity.todo.feature.tasks.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.featureSlot.combineStates
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.FirstRun
import com.singularity.todo.feature.tasks.presentation.state.FirstRunResolver
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailExtras
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskTimeSlotIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskAiSlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskBacklinksCollector
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskBacklinksState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskEntityState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskLogbookState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskRemindersState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskChildrenSlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskChildrenState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskCompletionSlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskDraftSlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskEntitySlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskLifecycleSlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskLogbookCollector
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskProposalsCollector
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskRemindersSlot
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState
import com.singularity.todo.feature.timetracking.presentation.slot.TaskTimeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Task detail screen — the coordinator for the task's slots.
 *
 * Assembles the screen state from one flow per slot and routes every intent to the slot
 * that owns it. The heavy lifting lives in the slots; this class owns only the task
 * subscription, the merge, and the event bus.
 *
 * ## The task subscription
 *
 * One subscription to [TaskRepository][com.singularity.todo.feature.tasks.domain.port.TaskRepository.observe]
 * feeds every slot through a shared [taskFlow]. The write into it happens in a `collect`
 * block, not inside a `combine` or `flatMapLatest` transform, and the draft is seeded there
 * too — once per task change rather than once per unrelated child update.
 *
 * ## What the merge covers
 *
 * Six inputs: the task row, the draft, entity metadata, child collections, reminders, and
 * backlinks. Completion, lifecycle, and AI state are intentionally absent — none of them
 * appear in [TaskDetailUi], so folding them in would recompute the whole screen on a
 * delete or an AI request for no visible change.
 *
 * The intent `when` below is exhaustive over [TaskDetailIntent.Domain]; adding a variant
 * without routing it is a compile error, which is the property that keeps this file from
 * becoming the 30-branch `when` it replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailCoordinator(
    private val deps: TaskDetailDeps,
    private val taskId: TaskId,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<TaskDetailUiState, TaskDetailIntent.Domain, TaskDetailUiEvent>(
        initialState = TaskDetailUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    private val loadable = MutableStateFlow<Task?>(null)
    private val taskFlow: StateFlow<Task?> = loadable.asStateFlow()
    private val retryVersion = MutableStateFlow(0)

    /**
     * Whether the repository has ANSWERED — separates "no answer yet" from "answered
     * and absent". The top-level combine reads this, not [taskFlow]: [taskFlow] starts
     * on a seeded null, so a combine fed directly by it emits `Error("Not found")` in
     * the instant before the repository's first emission, and every task-detail open
     * flashed the error screen.
     */
    private val taskLoad = MutableStateFlow<TaskLoad>(TaskLoad.Pending)

    private fun reportError(message: String) = tryEmit(TaskDetailUiEvent.Error(message))
    private fun reportSaved(message: String) = tryEmit(TaskDetailUiEvent.Saved(message))

    private val draft = TaskDraftSlot(deps.core, deps.context, vmScope, taskFlow, ::reportError)

    private val entity = TaskEntitySlot(taskId, deps.core, deps.children, vmScope, taskFlow, ::reportError)

    private val completion = TaskCompletionSlot(
        core = deps.core,
        context = deps.context,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
        onSaved = ::reportSaved,
    )

    private val children = TaskChildrenSlot(
        taskId = taskId,
        core = deps.core,
        children = deps.children,
        context = deps.context,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
        onSaved = ::reportSaved,
    )

    private val reminders = TaskRemindersSlot(
        taskId = taskId,
        core = deps.core,
        scheduling = deps.scheduling,
        context = deps.context,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
    )

    private val lifecycle = TaskLifecycleSlot(
        core = deps.core,
        scheduling = deps.scheduling,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
        onUndoDelete = { tryEmit(TaskDetailUiEvent.UndoDelete(it.id)) },
        onNavigateBack = { tryEmit(TaskDetailUiEvent.NavigateBack) },
        onSaved = ::reportSaved,
    )

    private val ai = TaskAiSlot(
        ai = deps.ai,
        collaboration = deps.collaboration,
        context = deps.context,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
        onSaved = ::reportSaved,
    )

    private val backlinks = TaskBacklinksCollector(
        collaboration = deps.collaboration,
        scope = vmScope,
        taskFlow = taskFlow,
    )

    private val logbook = TaskLogbookCollector(
        notesRepo = deps.collaboration.notesRepo,
        timeTrackingRepo = deps.collaboration.timeTrackingRepo,
        scope = vmScope,
        taskFlow = taskFlow,
    )

    private val timeSlot = TaskTimeSlot(
        taskId = taskId,
        timeTrackingRepo = deps.collaboration.timeTrackingRepo,
        currentUser = deps.collaboration.currentUser,
        scope = vmScope,
        taskFlow = taskFlow,
    )

    private val proposalsCollector = if (deps.collaboration.proposals != null) {
        TaskProposalsCollector(
            scope = vmScope,
            proposals = deps.collaboration.proposals.watchProposalsForTask(taskId),
        )
    } else {
        null
    }

    /**
     * Combines time tracking, first-run, and AI proposals into one partition.
     *
     * Deliberately NOT a `stateIn(WhileSubscribed)`: the running timer is one of the
     * inputs, and `WhileSubscribed(5_000)` keeps that upstream alive for five seconds
     * after the last subscriber goes away — which in a Compose UI test means the
     * recomposer never reaches idle and `captureToImage` blocks forever. A plain
     * `MutableStateFlow` fed by a `collect` is the canonical shape here.
     */
    private val extrasState: StateFlow<TaskDetailExtras> =
        MutableStateFlow<TaskDetailExtras>(TaskDetailExtras.Unresolved).also { sink ->
            scope.launch {
                combine(
                    timeSlot.state,
                    children.state,
                    taskFlow,
                    proposalsCollector?.state ?: flowOf(emptyList<AiProposal>()),
                ) { timeState, childrenState, task, proposals ->
                    Triple(timeState, childrenState, task) to proposals
                }.collect { (triple, proposals) ->
                    val (timeState, childrenState, task) = triple
                    sink.value = TaskDetailExtras.Ready(
                        timeSlotState = timeState,
                        firstRun = resolveFirstRun(childrenState, task),
                        proposals = proposals,
                    )
                }
            }
        }

    init {
        addCloseable(scope)
        scope.launch {
            combine(flowOf(taskId), retryVersion) { id, _ -> id }
                .flatMapLatest { deps.core.taskRepo.observe(it) }
                .catch { reportError(it.message ?: "Error") }
                .collect { task ->
                    loadable.value = task
                    taskLoad.value = if (task == null) TaskLoad.Missing else TaskLoad.Found(task)
                    if (task != null) draft.seed(task.title, task.description ?: "")
                }
        }
        scope.launch {
            combineStates(
                taskLoad,
                draft.state,
                entity.state,
                children.state,
                reminders.state,
                backlinks.state,
                logbook.state,
                extrasState,
                proposalsCollector?.state ?: flowOf(emptyList()),
            ) { load, draftState, entityState, childrenState, reminderState, backlinkState, logState, extras, _ ->
                when (load) {
                    is TaskLoad.Pending -> TaskDetailUiState.Loading

                    is TaskLoad.Missing -> TaskDetailUiState.Error("Not found")

                    is TaskLoad.Found -> loadedState(
                        task = load.task,
                        draftState = draftState,
                        entityState = entityState,
                        childrenState = childrenState,
                        reminderState = reminderState,
                        backlinkState = backlinkState,
                        logState = logState,
                        extras = extras,
                    )
                }
            }
                .catch { reportError(it.message ?: "Error") }
                .collect { setState(it) }
        }
    }

    /**
     * Builds the [TaskDetailUiState.Loaded] state from the nine combined inputs.
     *
     * Extracted from the top-level `combineStates` transform so the `when` over
     * [TaskLoad] stays single-line — a nested multi-line constructor inside a
     * `when` branch trips ktlint's IndentationRule (an analysis exception, not a finding).
     */
    @Suppress("LongParameterList")
    private fun loadedState(
        task: Task,
        draftState: TaskDetailDraft,
        entityState: TaskEntityState,
        childrenState: TaskChildrenState,
        reminderState: TaskRemindersState,
        backlinkState: TaskBacklinksState,
        logState: TaskLogbookState,
        extras: TaskDetailExtras,
    ): TaskDetailUiState.Loaded = TaskDetailUiState.Loaded(
        ui = TaskDetailUi(
            task = task,
            titleDraft = draftState.title,
            descriptionDraft = draftState.description,
            project = entityState.project,
            tags = entityState.tags,
            checklist = childrenState.checklist,
            reminders = reminderState.reminders,
            attachments = childrenState.attachments,
            subtasks = childrenState.subtasks,
            dependsOn = task.dependsOn,
            availableTasks = entityState.availableTasks,
            linkedNotes = backlinkState.notes,
            linkedTasks = backlinkState.tasks,
            logbookEntries = logState.allEntries,
            timeSlotState = when (val ex = extras) {
                is TaskDetailExtras.Unresolved -> TaskTimeSlotState.Idle
                is TaskDetailExtras.Ready -> ex.timeSlotState
            },
            firstRun = when (val ex = extras) {
                is TaskDetailExtras.Unresolved -> FirstRun.Unresolved
                is TaskDetailExtras.Ready -> ex.firstRun
            },
        ),
        extras = extras,
    )

    private fun resolveFirstRun(childrenState: TaskChildrenState, task: Task?): FirstRun = when {
        task == null -> FirstRun.Established

        else -> FirstRunResolver.resolve(
            hasBody = !task.description.isNullOrBlank(),
            checklistCount = childrenState.checklist.size,
            completedSubtaskCount = childrenState.subtasks.count { it.isCompleted },
            ageMs = (deps.context.clock.now() - task.createdAt).inWholeMilliseconds.coerceAtLeast(0L),
        )
    }

    /** Re-subscribes to the task after a load failure. */
    fun retry() {
        taskLoad.value = TaskLoad.Pending
        retryVersion.value++
    }

    // LongMethod and CyclomaticComplexMethod are both suppressed, and both for the
    // same reason: this is a dispatch table for a sealed hierarchy, and splitting
    // it up would move the routing away from the list of routes. The complexity
    // grew by four when the timer intents stopped being passed through as-is and
    // started being translated (#212) — each one needs its own construction now.
    // That is a real cost, paid deliberately: passing the object straight through
    // is precisely what made every timer intent land in a silent `else`.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun onIntent(intent: TaskDetailIntent.Domain) {
        when (intent) {
            is TaskDetailIntent.Domain.ToggleComplete -> completion.onIntent(intent)

            is TaskDetailIntent.Domain.TitleChanged,
            is TaskDetailIntent.Domain.DescriptionChanged,
            -> draft.onIntent(intent)

            is TaskDetailIntent.Domain.SetDueDate,
            is TaskDetailIntent.Domain.SetDueTime,
            is TaskDetailIntent.Domain.SetStartDate,
            is TaskDetailIntent.Domain.SetStartTime,
            is TaskDetailIntent.Domain.SetPriority,
            is TaskDetailIntent.Domain.SetProject,
            is TaskDetailIntent.Domain.SetTags,
            is TaskDetailIntent.Domain.RemoveTag,
            is TaskDetailIntent.Domain.SetKind,
            is TaskDetailIntent.Domain.ToggleSomeday,
            is TaskDetailIntent.Domain.TogglePinned,
            is TaskDetailIntent.Domain.SetRecurrence,
            is TaskDetailIntent.Domain.SetDependencies,
            is TaskDetailIntent.Domain.SetEstimate,
            -> entity.onIntent(intent)

            is TaskDetailIntent.Domain.ToggleChecklistItem,
            is TaskDetailIntent.Domain.DeleteChecklistItem,
            is TaskDetailIntent.Domain.AddChecklistItem,
            is TaskDetailIntent.Domain.ToggleSubtask,
            is TaskDetailIntent.Domain.DeleteSubtask,
            is TaskDetailIntent.Domain.AddSubtask,
            is TaskDetailIntent.Domain.AddUrlAttachment,
            is TaskDetailIntent.Domain.AddFileAttachment,
            is TaskDetailIntent.Domain.DeleteAttachment,
            -> children.onIntent(intent)

            is TaskDetailIntent.Domain.SetReminder,
            is TaskDetailIntent.Domain.DeleteReminder,
            -> reminders.onIntent(intent)

            is TaskDetailIntent.Domain.Delete,
            is TaskDetailIntent.Domain.Archive,
            is TaskDetailIntent.Domain.Restore,
            is TaskDetailIntent.Domain.Unarchive,
            -> lifecycle.onIntent(intent)

            is TaskDetailIntent.Domain.RunAiAction -> ai.onIntent(intent)

            is TaskDetailIntent.Domain.Start -> timeSlot.onIntent(TaskTimeSlotIntent.Start)

            is TaskDetailIntent.Domain.Stop -> timeSlot.onIntent(TaskTimeSlotIntent.Stop)

            is TaskDetailIntent.Domain.CreateManual -> timeSlot.onIntent(
                TaskTimeSlotIntent.CreateManual(
                    startedAtMs = intent.startedAtMs,
                    endedAtMs = intent.endedAtMs,
                    kind = intent.kind,
                    note = intent.note,
                ),
            )

            is TaskDetailIntent.Domain.Tick -> timeSlot.onIntent(TaskTimeSlotIntent.Tick(intent.elapsedMs))

            is TaskDetailIntent.Domain.ConfirmProposalItem -> {
                val apply = deps.collaboration.applyProposal ?: return
                val userId = deps.collaboration.currentUser.scopedUserId.value
                vmScope.launch {
                    apply.confirm(intent.itemId, userId).onFailure {
                        reportError("Confirm failed: ${it.message}")
                    }
                }
            }

            is TaskDetailIntent.Domain.RejectProposalItem -> {
                val apply = deps.collaboration.applyProposal ?: return
                val userId = deps.collaboration.currentUser.scopedUserId.value
                vmScope.launch {
                    apply.reject(intent.itemId, userId, intent.reason).onFailure {
                        reportError("Reject failed: ${it.message}")
                    }
                }
            }

            is TaskDetailIntent.Domain.ConfirmAllProposalItems -> {
                val apply = deps.collaboration.applyProposal ?: return
                val userId = deps.collaboration.currentUser.scopedUserId.value
                vmScope.launch {
                    val batch = apply.confirmAll(intent.proposalId, userId)
                    if (batch.failed.isNotEmpty()) {
                        reportError("${batch.failed.size} items could not be applied")
                    }
                }
            }

            is TaskDetailIntent.Domain.DismissProposal -> {
                val proposals = deps.collaboration.proposals ?: return
                val userId = deps.collaboration.currentUser.scopedUserId.value
                vmScope.launch {
                    proposals.retract(intent.proposalId, userId).onFailure {
                        reportError("Dismiss failed: ${it.message}")
                    }
                }
            }
        }
    }

    /**
     * Repository answer state for the observed task. Kept private: only the top-level
     * combine branches on it — slots keep consuming [Task] via `taskFlow`.
     */
    private sealed interface TaskLoad {
        /** The repository has not answered yet — the screen stays on its loading shell. */
        data object Pending : TaskLoad

        /** The repository answered: the task exists. */
        data class Found(val task: Task) : TaskLoad

        /** The repository answered: no such task for the current user. */
        data object Missing : TaskLoad
    }
}
