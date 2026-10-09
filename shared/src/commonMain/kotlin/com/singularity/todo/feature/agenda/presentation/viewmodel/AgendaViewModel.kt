package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

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
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
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

    // ── Selection state ──────────────────────────────────────────────────────────
    //
    // Selection has no fields of its own. It lives in `AgendaUiState.Loaded`,
    // because that is what the screen renders, and it used to also live in a pair
    // of `MutableStateFlow`s with `updateSelectionState()` copying between them —
    // two writers for one value, bridged by hand, with nothing enforcing that a
    // fourth handler remembered to call the bridge.

    // ── Undo-delete state ────────────────────────────────────────────────────────

    /**
     * The item a delete is holding open for undo, or null when none is.
     *
     * Public because the screen's snackbar is driven by it and dismissed when it
     * clears — see `AgendaScreen`. This is the ADR's in-memory marker, not a second
     * copy of state the screen renders: nothing else reads it, and the undo window
     * is exactly as long as this is set.
     */
    private val _pendingDelete = MutableStateFlow<PendingDelete?>(null)
    val pendingDelete = _pendingDelete.asStateFlow()

    /** Cooldown job for clearing [_pendingDelete] after the undo window expires. */
    private var pendingDeleteJob: Job? = null

    /**
     * Identity of the pending-delete slot.
     *
     * The scope runs on `Dispatchers.Default` — a multi-threaded pool — so two
     * deletes dispatched in quick succession genuinely interleave, and the first
     * one's timer can still be live when the second claims the slot. Cancelling
     * the job is best-effort there; comparing generations is not.
     */
    private val undoSlot = AtomicInt(0)

    /**
     * Order in which deletes were *requested*, as opposed to the order they finished.
     *
     * Separate from [undoSlot] on purpose. [undoSlot] identifies who owns the single
     * undo affordance and is advanced only on success, so a failed delete cannot
     * evict a recoverable one. This one is advanced at dispatch time, so a delete
     * that finishes late knows it has been superseded and does not claim the slot.
     */
    private val deleteSequence = AtomicInt(0)

    init {
        scope.launch {
            todayFlow(deps.clock, deps.timeZone.current()).flatMapLatest { today ->
                deps.taskRepo.observeByFilter(TaskFilter.All)
                    .map { tasks ->
                        AgendaUiState.Loaded(
                            sections = AgendaEvaluator.evaluate(tasks, definition, today),
                            today = today,
                        )
                    }
            }
                .collect { fresh ->
                    // Reconciliation goes through `updateState`, which is CAS-backed,
                    // because this collector and the selection handlers both write and
                    // the scope is multi-threaded. Reading `_state.value` and then
                    // `setState`-ing the result — which is what this used to do —
                    // is a read-modify-write that can drop a concurrent selection.
                    updateState { current ->
                        val previous = (current as? AgendaUiState.Loaded)?.selectedTaskIds ?: emptySet()
                        // A task that has left the agenda — completed or deleted
                        // elsewhere — must not stay selected: the id would otherwise
                        // ride along into a later bulk delete.
                        val kept = previous.intersect(fresh.taskIds())
                        fresh.copy(isSelectionMode = kept.isNotEmpty(), selectedTaskIds = kept)
                    }
                }
        }
    }

    private fun AgendaUiState.Loaded.taskIds(): Set<TaskId> =
        sections.flatMapTo(mutableSetOf()) { section -> section.tasks.map { it.task.id } }

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
                if ((currentState as? AgendaUiState.Loaded)?.isSelectionMode == true) {
                    scope.launch { handleToggleSelection(taskId) }
                } else {
                    scope.launch { emit(AgendaUiEvent.NavigateToTask(taskId)) }
                }
            }

            is AgendaIntent.TaskCheckClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.toggleComplete(taskId)
                        .onFailure { report(it, TOGGLE_COMPLETE_FAILED, "Could not update task") }
                }
            }

            is AgendaIntent.TaskLongClicked -> with(intent) {
                if ((currentState as? AgendaUiState.Loaded)?.isSelectionMode == true) {
                    // Already in selection mode — ignore long-press, click handles selection.
                } else {
                    scope.launch { handleEnterSelectionMode(taskId) }
                }
            }

            is AgendaIntent.TaskPinClicked -> with(intent) {
                scope.launch {
                    deps.taskRepo.togglePinned(taskId)
                        .onFailure { report(it, TOGGLE_PINNED_FAILED, "Could not update task") }
                }
            }

            is AgendaIntent.TaskDeleteClicked -> with(intent) {
                scope.launch { handleTaskDelete(intent.taskId) }
            }

            is AgendaIntent.UndoDeleteTapped -> {
                scope.launch { handleUndoDeleteTapped() }
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
    //
    // Every one of these is a single `updateState`, so the selection has exactly one
    // home and one writer path. They used to write a pair of `MutableStateFlow`s and
    // then call `updateSelectionState()` to copy them into the state — so each handler
    // had to remember the second step, and nothing enforced that a new one would.

    /** The ids currently selected, read from the single state. */
    private fun selectedIds(): Set<TaskId> =
        (currentState as? AgendaUiState.Loaded)?.selectedTaskIds ?: emptySet()

    /** Enter multi-selection mode, selecting [taskId]. */
    private fun handleEnterSelectionMode(taskId: TaskId) = updateState { current ->
        (current as? AgendaUiState.Loaded)?.copy(
            isSelectionMode = true,
            selectedTaskIds = setOf(taskId),
        ) ?: current
    }

    /**
     * Toggle [taskId] in the current selection.
     *
     * Deselecting the last task leaves selection mode, because the action row and
     * the per-row checkbox are both driven by that flag.
     */
    private fun handleToggleSelection(taskId: TaskId) = updateState { current ->
        val loaded = current as? AgendaUiState.Loaded ?: return@updateState current
        val updated = if (taskId in loaded.selectedTaskIds) {
            loaded.selectedTaskIds - taskId
        } else {
            loaded.selectedTaskIds + taskId
        }
        loaded.copy(isSelectionMode = updated.isNotEmpty(), selectedTaskIds = updated)
    }

    /** Exit selection mode, clearing all selected tasks. */
    private fun handleExitSelectionMode() = updateState { current ->
        (current as? AgendaUiState.Loaded)?.copy(
            isSelectionMode = false,
            selectedTaskIds = emptySet(),
        ) ?: current
    }

    /**
     * Delete all selected tasks via [TaskMutationsUseCase.bulkDelete].
     * Exits selection mode on completion.
     */
    private suspend fun handleBulkDelete() {
        val ids = selectedIds().toList()
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
        val ids = selectedIds().toList()
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

    // ── Undo-delete handlers ────────────────────────────────────────────────────

    /**
     * Soft-deletes a task, then offers an undo for as long as [_pendingDelete] holds
     * the entry.
     *
     * The affordance *is* the marker, not an event: the screen renders it by
     * watching [pendingDelete] and dismisses it when the marker clears. An
     * `UndoDelete` event carrying the same id and title existed alongside it and was
     * collected into an empty branch — two channels for one fact, one of them dead
     * from the day it was written.
     *
     * ## What this used to do
     *
     * It announced a delete it never issued. `taskRepo.softDelete` appeared nowhere
     * in this class, so tapping delete produced a snackbar saying `"X" deleted`
     * while the task stayed live, kept its reminders, and could still be restored
     * by an Undo that called `restore` against a row that was never archived.
     * `AgendaDeps` carried `reminderScheduler` and `currentUser` documented as
     * being for exactly this call, and neither was referenced.
     *
     * ## Order
     *
     * Reminders are cancelled **before** the delete, and a cancellation failure
     * aborts the delete. The invariant that buys is one-directional: a reminder
     * never outlives its task. `cancelByTask` returns `Unit` and hands back no
     * `Reminder` spec, so the reverse cannot be compensated for — a delete that
     * fails after a successful cancellation leaves the task without its reminder.
     * That is the accepted trade; a lost reminder is recoverable, a zombie reminder
     * for a deleted task is not.
     */
    private fun handleTaskDelete(taskId: TaskId) {
        // Find the task title from the current state for the snackbar label.
        val taskTitle = findTaskTitle(taskId)
        // Claim the *request* order up front. Two deletes dispatched in quick
        // succession genuinely interleave on Dispatchers.Default, so the one that
        // reaches the repository last is not necessarily the one the user asked for
        // last — and the newer request is the one whose undo they are looking at.
        val request = deleteSequence.incrementAndFetch()

        scope.launch {
            // `runCatchingCancellable` rather than a try/catch: cancellation must still
            // propagate, and it hands back a Result, which is what the rest of this
            // function already speaks.
            runCatchingCancellable {
                deps.reminderScheduler.cancelByTask(taskId, deps.currentUser.scopedUserId.value)
            }.onFailure {
                report(it, CANCEL_REMINDERS_FAILED, "Could not delete — its reminder is still scheduled")
                return@launch
            }

            val deleted = deps.taskRepo.softDelete(taskId)
            if (deleted.isFailure) {
                report(
                    deleted.exceptionOrNull() ?: IllegalStateException("delete failed"),
                    SOFT_DELETE_FAILED,
                    "Delete failed",
                )
                return@launch
            }

            // Superseded while in flight: the task is genuinely deleted, but a newer
            // request owns the single affordance and this one does not compete for it.
            if (deleteSequence.load() != request) return@launch

            // Claimed only now — a failed delete must not evict a good pending undo.
            val generation = undoSlot.incrementAndFetch()
            pendingDeleteJob?.cancel()
            _pendingDelete.value = PendingDelete(taskId, taskTitle)

            pendingDeleteJob = scope.launch {
                delay(UNDO_WINDOW_MS)
                // Generation-scoped: a superseded timer can still be live when a newer
                // delete claims the slot. `cancel()` is best-effort on that path —
                // the timer may already have resumed — and this check is not.
                if (undoSlot.load() == generation) _pendingDelete.value = null
            }
        }
    }

    /**
     * Restores the item held by [_pendingDelete].
     *
     * The marker is cleared **only on success**, and the timer is left running
     * until then. A failed reversal must leave the offer addressable: clearing the
     * pending slot before knowing the write worked removes the only recovery path
     * at the exact moment the user needs it, and dismisses the snackbar with it, so
     * the failure becomes invisible. See `delete-safety-feedback` Phase 1 and #78.
     *
     * `restore` re-uses the original id, so a repeated attempt is safe rather than
     * impossible — the timer bounds how long the offer stays up.
     */
    private fun handleUndoDeleteTapped() = scope.launch {
        val pending = _pendingDelete.value ?: return@launch
        deps.taskRepo.restore(pending.taskId)
            .onSuccess {
                pendingDeleteJob?.cancel()
                _pendingDelete.value = null
            }
            .onFailure { report(it, RESTORE_FAILED, "Could not restore") }
    }

    /** Reports a failure to the crash reporter and to the user. */
    private suspend fun report(error: Throwable, label: String, message: String) {
        crashReporter.report(error, label)
        emit(AgendaUiEvent.ShowError(message))
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
        private const val CANCEL_REMINDERS_FAILED = "agenda.cancel_reminders_failed"
        private const val SOFT_DELETE_FAILED = "agenda.soft_delete_failed"
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
