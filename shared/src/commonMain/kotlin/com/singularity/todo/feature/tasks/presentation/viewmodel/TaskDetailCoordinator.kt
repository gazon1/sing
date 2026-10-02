package com.singularity.todo.feature.tasks.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
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
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskAiSlot
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskBacklinksCollector
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
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TaskDetailUiState, TaskDetailIntent.Domain, TaskDetailUiEvent>(
        initialState = TaskDetailUiState.Loading,
        scope = scope,
    ) {

    private val loadable = MutableStateFlow<Task?>(null)
    private val taskFlow: StateFlow<Task?> = loadable.asStateFlow()
    private val retryVersion = MutableStateFlow(0)

    private fun reportError(message: String) = tryEmit(TaskDetailUiEvent.Error(message))
    private fun reportSaved(message: String) = tryEmit(TaskDetailUiEvent.Saved(message))

    private val draft = TaskDraftSlot(deps, vmScope, taskFlow, ::reportError)

    private val entity = TaskEntitySlot(taskId, deps, vmScope, taskFlow, ::reportError)

    private val completion = TaskCompletionSlot(deps, vmScope, taskFlow, ::reportError, ::reportSaved)

    private val children = TaskChildrenSlot(
        taskId = taskId,
        deps = deps,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
        onSaved = ::reportSaved,
    )

    private val reminders = TaskRemindersSlot(taskId, deps, vmScope, taskFlow, ::reportError)

    private val lifecycle = TaskLifecycleSlot(
        deps = deps,
        scope = vmScope,
        taskFlow = taskFlow,
        onError = ::reportError,
        onUndoDelete = { tryEmit(TaskDetailUiEvent.UndoDelete(it.id)) },
        onNavigateBack = { tryEmit(TaskDetailUiEvent.NavigateBack) },
        onSaved = ::reportSaved,
    )

    private val ai = TaskAiSlot(deps, vmScope, taskFlow, ::reportError, ::reportSaved)

    private val backlinks = TaskBacklinksCollector(deps, vmScope, taskFlow)

    private val logbook = TaskLogbookCollector(deps.notesRepo, deps.timeTrackingRepo, vmScope, taskFlow)

    private val timeSlot = TaskTimeSlot(
        taskId = taskId,
        timeTrackingRepo = deps.timeTrackingRepo,
        currentUser = deps.currentUser,
        scope = vmScope,
        taskFlow = taskFlow,
    )

    private val proposalsCollector = if (deps.proposals != null) {
        TaskProposalsCollector(
            scope = vmScope,
            proposals = deps.proposals.watchProposalsForTask(taskId, deps.currentUser.scopedUserId.value),
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
            vmScope.launch {
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
                .flatMapLatest { deps.taskRepo.observe(it) }
                .catch { reportError(it.message ?: "Error") }
                .collect { task ->
                    loadable.value = task
                    if (task != null) draft.seed(task.title, task.description ?: "")
                }
        }
        scope.launch {
            combineStates(
                taskFlow,
                draft.state,
                entity.state,
                children.state,
                reminders.state,
                backlinks.state,
                logbook.state,
                extrasState,
                proposalsCollector?.state ?: flowOf(emptyList()),
            ) { task, draftState, entityState, childrenState, reminderState, backlinkState, logState, extras, _ ->
                if (task == null) {
                    TaskDetailUiState.Error("Not found")
                } else {
                    TaskDetailUiState.Loaded(
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
                }
            }
                .catch { reportError(it.message ?: "Error") }
                .collect { setState(it) }
        }
    }

    private fun resolveFirstRun(childrenState: TaskChildrenState, task: Task?): FirstRun = when {
        task == null -> FirstRun.Established

        else -> FirstRunResolver.resolve(
            hasBody = !task.description.isNullOrBlank(),
            checklistCount = childrenState.checklist.size,
            completedSubtaskCount = childrenState.subtasks.count { it.isCompleted },
            ageMs = (deps.clock.now() - task.createdAt).inWholeMilliseconds.coerceAtLeast(0L),
        )
    }

    /** Re-subscribes to the task after a load failure. */
    fun retry() {
        retryVersion.value++
    }

    @Suppress("LongMethod")
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

            is TaskDetailIntent.Domain.Start,
            is TaskDetailIntent.Domain.Stop,
            is TaskDetailIntent.Domain.CreateManual,
            is TaskDetailIntent.Domain.Tick,
            -> timeSlot.onIntent(intent)

            is TaskDetailIntent.Domain.ConfirmProposalItem -> {
                val apply = deps.applyProposal ?: return
                val userId = deps.currentUser.scopedUserId.value
                vmScope.launch {
                    apply.confirm(intent.itemId, userId).onFailure {
                        reportError("Confirm failed: ${it.message}")
                    }
                }
            }

            is TaskDetailIntent.Domain.RejectProposalItem -> {
                val apply = deps.applyProposal ?: return
                val userId = deps.currentUser.scopedUserId.value
                vmScope.launch {
                    apply.reject(intent.itemId, userId, intent.reason).onFailure {
                        reportError("Reject failed: ${it.message}")
                    }
                }
            }

            is TaskDetailIntent.Domain.ConfirmAllProposalItems -> {
                val apply = deps.applyProposal ?: return
                val userId = deps.currentUser.scopedUserId.value
                vmScope.launch {
                    val batch = apply.confirmAll(intent.proposalId, userId)
                    if (batch.failed.isNotEmpty()) {
                        reportError("${batch.failed.size} items could not be applied")
                    }
                }
            }

            is TaskDetailIntent.Domain.DismissProposal -> {
                val proposals = deps.proposals ?: return
                val userId = deps.currentUser.scopedUserId.value
                vmScope.launch {
                    proposals.retract(intent.proposalId, userId).onFailure {
                        reportError("Dismiss failed: ${it.message}")
                    }
                }
            }
        }
    }
}
