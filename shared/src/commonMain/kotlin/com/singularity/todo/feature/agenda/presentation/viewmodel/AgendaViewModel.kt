package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.platform.todayFlow
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ViewModel for the Agenda screen.
 *
 * Data flow:
 * 1. [deps.currentUser.scopedUserId] + [deps.clock.todayFlow()] → [flatMapLatest] → [watchTasks] with [TaskFilter.All]
 * 2. All tasks are evaluated against [definition] via [AgendaEvaluator.evaluate]
 * 3. [AgendaUiState.Loaded] → [state]
 * 4. One-shot events (task click → navigate) → [events]
 *
 * Both user switch and date change trigger re-evaluation.
 * [distinctUntilChanged] suppresses redundant evaluations when only the instant changes.
 *
 * @param deps Injected dependencies (task repository, current user, clock, logger).
 * @param definition The agenda definition to evaluate. In MR1 this does not change
 *        at runtime; future MRs will support switching definitions.
 * @param scope CoroutineScope for all coroutine work. Tests pass `this` (TestScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModel(
    private val deps: AgendaDeps,
    definition: AgendaDefinition,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<AgendaUiState, AgendaIntent, AgendaUiEvent>(
        initialState = AgendaUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * The definition being evaluated — stable reference.
     *
     * Declared before the `init` block because that block's coroutine reads it.
     * Kotlin runs property initializers and `init` blocks in declaration order, so
     * a property declared after such an `init` is still null when the coroutine
     * body first runs. See `ProjectDetailViewModel.draftState` for the same
     * hazard, where it did fire as an NPE.
     */
    val definition: AgendaDefinition = definition

    // ── Selection state ───────────────────────────────────────────────────────────

    /** Currently selected task IDs. Persists across agenda re-evaluations. */
    private val _selectedTaskIds = MutableStateFlow<Set<TaskId>>(emptySet())
    val selectedTaskIds = _selectedTaskIds.asStateFlow()

    /** True when the user is in multi-selection mode. */
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode = _isSelectionMode.asStateFlow()

    // ── Undo-delete state ────────────────────────────────────────────────────────

    /**
     * Tracks a soft-deleted task pending undo, so the snackbar can offer a 5-second window.
     * Null when no delete is pending.
     */
    private val _pendingDelete = MutableStateFlow<PendingDelete?>(null)
    val pendingDelete = _pendingDelete.asStateFlow()

    /** Cooldown job for clearing [_pendingDelete] after the undo window expires. */
    private var pendingDeleteJob: Job? = null

    init {
        scope.launch {
            todayFlow(deps.clock, deps.timeZone.current()).flatMapLatest { today ->
                deps.taskRepo.observeByFilter(TaskFilter.All)
                    .map { tasks ->
                        val sections = AgendaEvaluator.evaluate(tasks, definition, today)
                        // Preserve selection state across re-evaluations.
                        AgendaUiState.Loaded(
                            sections = sections,
                            today = today,
                            isSelectionMode = _isSelectionMode.value,
                            selectedTaskIds = _selectedTaskIds.value,
                        )
                    }
            }
                .collect { loaded -> setState(loaded) }
        }
    }

    /** Title derived from the definition, for the Slot API. */
    val title: String get() = definition.title

    /**
     * Processes a user [AgendaIntent].
     *
     * The three repository mutations below discard their `Result`, and [AgendaUiEvent] has no
     * error variant to route a failure into — so they report and keep the previous behaviour
     * (nothing visible happens). Adding a user-visible error here means adding an event and
     * handling it in the screen; that is a product change, deliberately not smuggled in with a
     * reliability fix. A *thrown* failure on these paths — including from [emit] on a closed
     * event channel — reaches `BackgroundFailureHandler` via the scope instead.
     */
    @Suppress("CyclomaticComplexMethod") // 14 intents is intentional; extract when this grows further
    override fun onIntent(intent: AgendaIntent) {
        when (intent) {
            is AgendaIntent.TaskClicked -> with(intent) {
                if (_isSelectionMode.value) {
                    scope.launch { handleToggleSelection(taskId) }
                } else {
                    scope.launch { emit(AgendaUiEvent.NavigateToTask(taskId)) }
                }
            }

            is AgendaIntent.TaskCheckClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.toggleComplete(taskId)
                        .onFailure { crashReporter.report(it, TOGGLE_COMPLETE_FAILED) }
                }
            }

            is AgendaIntent.TaskLongClicked -> with(intent) {
                if (_isSelectionMode.value) {
                    // Already in selection mode — ignore long-press, click handles selection.
                } else {
                    scope.launch { handleEnterSelectionMode(taskId) }
                }
            }

            is AgendaIntent.TaskPinClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.togglePinned(taskId)
                        .onFailure { crashReporter.report(it, TOGGLE_PINNED_FAILED) }
                }
            }

            is AgendaIntent.TaskDeleteClicked -> with(intent) {
                scope.launch { handleTaskDelete(intent.taskId) }
            }

            is AgendaIntent.TaskExpandClicked -> with(intent) {
                scope.launch { emit(AgendaUiEvent.ExpandTask(taskId)) }
            }

            is AgendaIntent.CreateInSection -> with(intent) {
                scope.launch { handleCreateInSection(intent.sectionId) }
            }

            is AgendaIntent.EnterSelectionMode -> with(intent) {
                scope.launch { handleEnterSelectionMode(intent.taskId) }
            }

            is AgendaIntent.ToggleSelection -> with(intent) {
                scope.launch { handleToggleSelection(intent.taskId) }
            }

            is AgendaIntent.ExitSelectionMode -> with(intent) {
                handleExitSelectionMode()
            }

            is AgendaIntent.DeleteSelected -> with(intent) {
                scope.launch { handleBulkDelete() }
            }

            is AgendaIntent.CompleteSelected -> with(intent) {
                scope.launch { handleBulkComplete() }
            }
        }
    }

    // ── Selection handlers ──────────────────────────────────────────────────────

    /**
     * Enter multi-selection mode, selecting [taskId].
     * Called from long-press or explicit enter action.
     */
    private fun handleEnterSelectionMode(taskId: TaskId) {
        _isSelectionMode.value = true
        _selectedTaskIds.value = setOf(taskId)
        updateSelectionState()
    }

    /**
     * Toggle [taskId] in the current selection.
     * If it was the last selected task, exits selection mode.
     */
    private fun handleToggleSelection(taskId: TaskId) {
        val current = _selectedTaskIds.value
        val updated = if (taskId in current) current - taskId else current + taskId
        _selectedTaskIds.value = updated
        if (updated.isEmpty()) {
            _isSelectionMode.value = false
        }
        updateSelectionState()
    }

    /** Exit selection mode, clearing all selected tasks. */
    private fun handleExitSelectionMode() {
        _isSelectionMode.value = false
        _selectedTaskIds.value = emptySet()
        updateSelectionState()
    }

    /**
     * Delete all selected tasks via [TaskMutationsUseCase.bulkDelete].
     * Exits selection mode on completion.
     */
    private suspend fun handleBulkDelete() {
        val ids = _selectedTaskIds.value.toList()
        if (ids.isEmpty()) return

        val result = deps.taskMutations.bulkDelete(ids)
        handleExitSelectionMode()

        result.fold(
            onSuccess = {
                emit(AgendaUiEvent.BulkOperationDone(count = ids.size, operation = "deleted"))
            },
            onFailure = { error ->
                crashReporter.report(error, BULK_DELETE_FAILED)
                emit(AgendaUiEvent.BulkOperationDone(count = 0, operation = "deleted", error = error.message))
            },
        )
    }

    /**
     * Complete all selected tasks via [TaskMutationsUseCase.bulkComplete].
     * Exits selection mode on completion.
     */
    private suspend fun handleBulkComplete() {
        val ids = _selectedTaskIds.value.toList()
        if (ids.isEmpty()) return

        val result = deps.taskMutations.bulkComplete(ids)
        handleExitSelectionMode()

        result.fold(
            onSuccess = {
                emit(AgendaUiEvent.BulkOperationDone(count = ids.size, operation = "completed"))
            },
            onFailure = { error ->
                crashReporter.report(error, BULK_COMPLETE_FAILED)
                emit(AgendaUiEvent.BulkOperationDone(count = 0, operation = "completed", error = error.message))
            },
        )
    }

    /** Push current selection state into [setState] so the screen re-renders. */
    private fun updateSelectionState() {
        val current = _state.value as? AgendaUiState.Loaded ?: return
        setState(
            current.copy(
                isSelectionMode = _isSelectionMode.value,
                selectedTaskIds = _selectedTaskIds.value,
            ),
        )
    }

    // ── Undo-delete handlers ────────────────────────────────────────────────────

    /**
     * Soft-deletes a task and emits [AgendaUiEvent.UndoDelete] so the UI can show a snackbar.
     * The snackbar offers a 5-second undo window; if not tapped, the pending delete is cleared.
     * If the user taps Undo, [onUndoDelete] calls [restore] to reverse the delete.
     */
    private suspend fun handleTaskDelete(taskId: TaskId) {
        // Find the task title from the current state for the snackbar label.
        val taskTitle = findTaskTitle(taskId)

        // Cancel any existing undo window — a new delete supersedes it.
        pendingDeleteJob?.cancel()

        // Store the pending delete and emit the event.
        _pendingDelete.value = PendingDelete(taskId, taskTitle)
        emit(AgendaUiEvent.UndoDelete(taskId, taskTitle))

        // Kick off the 5-second undo window.
        pendingDeleteJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            _pendingDelete.value = null
        }
    }

    /**
     * Restores the last soft-deleted task, cancelling the undo window.
     * Called when the user taps "Undo" on the snackbar.
     */
    private suspend fun onUndoDelete(taskId: TaskId) {
        pendingDeleteJob?.cancel()
        _pendingDelete.value = null
        deps.taskRepo.restore(taskId)
            .onFailure { crashReporter.report(it, RESTORE_FAILED) }
    }

    /**
     * Call this from the UI when the user taps "Undo" on the snackbar.
     * The UI layer holds the snackbar reference and invokes this method directly.
     */
    fun onUndoDeleteIntent() {
        val pending = _pendingDelete.value ?: return
        scope.launch { onUndoDelete(pending.taskId) }
    }

    private fun findTaskTitle(taskId: TaskId): String {
        val loaded = _state.value as? AgendaUiState.Loaded ?: return "Task"
        for (section in loaded.sections) {
            for (row in section.tasks) {
                if (row.task.id == taskId) return row.task.title.ifEmpty { "Task" }
            }
        }
        return "Task"
    }

    private suspend fun handleCreateInSection(sectionId: String) {
        // Find the section definition
        val section = definition.sections.find { it.effectiveId == sectionId } ?: return
        val sectionPrefill = section.prefill ?: return

        // A bucket resolves against *today*, not against the day the definition
        // was written. The presets used to carry hardcoded LocalDates, which
        // meant tapping '+' in the Today section on any other day prefilled that
        // one date — a saved view is a template, not a snapshot of a day.
        // `todayAt` requires the zone explicitly (#91). It used to default to the
        // host's, which made a saved view resolve 'today' differently on two machines
        // with the same fake clock — a template that is not reproducible.
        val resolvedDue = sectionPrefill.dueDate
            ?: sectionPrefill.relativeDueDate
                ?.toDateRange(todayAt(deps.clock, deps.timeZone.current()))
                ?.from

        // Build a TaskDraft from the section prefill
        val draft = TaskDraft(
            title = sectionPrefill.title ?: "",
            dueDate = resolvedDue?.let { DueDateOption.Custom(it, it.toString()) }
                ?: DueDateOption.None,
        )

        // Save to DraftStore under section-specific key
        val key = "section_create_draft_$sectionId"
        deps.draftStore.save(key, draft, TaskDraft.serializer())

        emit(AgendaUiEvent.CreateInSection(sectionId))
    }

    companion object {
        /** 5-second undo window, matching the snackbar duration. */
        const val UNDO_WINDOW_MS = 5_000L

        // Machine-shaped grouping keys — these leave the device.
        private const val TOGGLE_COMPLETE_FAILED = "agenda.toggle_complete_failed"
        private const val TOGGLE_PINNED_FAILED = "agenda.toggle_pinned_failed"
        private const val RESTORE_FAILED = "agenda.restore_failed"
        private const val BULK_DELETE_FAILED = "agenda.bulk_delete_failed"
        private const val BULK_COMPLETE_FAILED = "agenda.bulk_complete_failed"
    }
}

/**
 * A task that has been soft-deleted and is pending an undo window.
 *
 * @param taskId The deleted task id.
 * @param taskTitle Short label for the snackbar.
 */
data class PendingDelete(val taskId: TaskId, val taskTitle: String)
