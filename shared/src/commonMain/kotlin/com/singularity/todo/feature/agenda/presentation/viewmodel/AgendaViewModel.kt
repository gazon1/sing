package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.localTimeAt
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.platform.todayFlow
import com.singularity.todo.core.ui.components.CountdownStateMachine
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.model.BlockedBulkCloseRequest
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.BlockedTasksException
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.toImmutableList
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
// 18 functions: one handler per intent — the house shape of this screen. The
// bulk-close path adds two of them (the question, and the confirmed close),
// and a handler per intent is what keeps the intents from growing a private
// helper each.
@Suppress("TooManyFunctions")
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

    /**
     * Today, in the user's own time zone.
     *
     * The date picker in the top bar has to mark today, and a picker that
     * marked the host's day would be wrong for every user east or west of it.
     * The zone is the one the agenda itself resolves its dates in, so the two
     * cannot disagree about which day it is.
     */
    val today: kotlinx.datetime.LocalDate = todayAt(deps.clock, deps.timeZone.current())

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

    /** Drives the undo snackbar countdown. Uses [UNDO_WINDOW_MS] and tick counting for virtual-time test compatibility. */
    private val countdown = CountdownStateMachine(
        scope = scope,
        windowMs = UNDO_WINDOW_MS,
        onExpired = { _pendingDelete.value = null },
    )

    /** Exposes countdown progress to the screen's snackbar progress bar. */
    val countdownProgress: StateFlow<Float?> = countdown.progress

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

    /**
     * Drives the greeting computation.
     *
     * Uses [MutableStateFlow] so the value is observable from tests without a Compose
     * runtime. Initialized in [init] rather than inside the `collect` block so tests
     * can drain it with a bounded number of [runCurrent] calls — no `advanceUntilIdle`
     * (which hangs on the infinite [todayFlow] loop) and no `runBlocking` (forbidden
     * by [NoRunBlockingRule]).
     *
     * @see <docs/decisions/2026-10-18-greeting-init-no-runblocking.md>
     */
    // Non-null default breaks the tie before computeGreeting() finishes.
    // "Good evening" is the most common outcome; the computed value replaces it within
    // the same runCurrent() window in tests, so there is no flash of the wrong greeting.
    private val _greeting = MutableStateFlow("Good evening")
    val greeting: StateFlow<String> = _greeting.asStateFlow()

    /**
     * The task list the current evaluation was made against.
     *
     * The confirmation needs to know which prerequisites are still open, and
     * answering that needs the tasks those prerequisites are. The evaluation
     * already had them, and it is the *same* list the "blocked" badge was
     * computed from — so the badge and the confirmation cannot disagree about
     * whether a task is blocked.
     */
    private val latestTasks = MutableStateFlow<List<Task>>(emptyList())

    /** Increments to re-run the collection in [init] after a failure or a retry. */
    private val _reloadTrigger = MutableStateFlow(0)

    init {
        // Greeting is computed once at load from greetingSettings and the current clock hour.
        // It does not change during the lifetime of this ViewModel instance.
        // Launched here (not inside the collector) so tests can drain it with runCurrent:
        // the collect chain and the greeting computation are independent coroutines on the
        // same scope, and runCurrent advances one pending task per call, so the greeting
        // computation completes in 3-4 calls before the collect callback reads _greeting.value.
        scope.launch {
            _greeting.value = computeGreeting()
        }

        scope.launch {
            _reloadTrigger.flatMapLatest { _ ->
                todayFlow(deps.clock, deps.timeZone.current()).flatMapLatest { today ->
                    // The projects are observed alongside the tasks: the row needs its
                    // project's name and colour, and the lookup includes closed projects
                    // so a badge never vanishes because the project was finished. The
                    // projects are resolved here — once per list build — and ride on the
                    // row item, so no row looks a project up.
                    combine(
                        deps.taskRepo.observeByFilter(TaskFilter.All)
                            .distinctUntilChanged(), // skip recompute when task list unchanged
                        deps.projectsRepository.observeAllIncludingClosed(),
                    ) { tasks, projects -> tasks to projects }
                        .onEach { (tasks, _) -> latestTasks.value = tasks }
                        .map { (tasks, projects) ->
                            val projectsById = projects.associateBy { it.id.value }
                            AgendaUiState.Loaded(
                                sections = AgendaEvaluator
                                    .evaluate(tasks, definition, today, deps.clock.now(), projectsById)
                                    .toImmutableList(),
                                today = today,
                                greeting = _greeting.value,
                            )
                        }
                }
            }
                .catch { cause ->
                    setState(AgendaUiState.Error(message = cause.toMessage("Could not load agenda")))
                }
                .collect { fresh ->
                    // Reconciliation goes through `updateState`, which is CAS-backed,
                    // because this collector and the selection handlers both write and
                    // the scope is multi-threaded. Reading `_state.value` and then
                    // `setState`-ing the result — which is what this used to do —
                    // is a read-modify-write that can drop a concurrent selection.
                    updateState { current ->
                        val previous = (current as? AgendaUiState.Loaded)?.selectedTaskIds ?: persistentSetOf()
                        // A task that has left the agenda — completed or deleted
                        // elsewhere — must not stay selected: the id would otherwise
                        // ride along into a later bulk delete.
                        val kept = previous.intersect(fresh.taskIds()).toImmutableSet()
                        fresh.copy(
                            isSelectionMode = kept.isNotEmpty(),
                            selectedTaskIds = kept,
                        )
                    }
                }
        }
    }

    /** Computes the time-of-day greeting from [greetingSettings] and the current clock hour. */
    private suspend fun computeGreeting(): String = runCatchingCancellable {
        val morningEnd = deps.greetingSettings.morningEndHour.first()
        val afternoonEnd = deps.greetingSettings.afternoonEndHour.first()
        // localTimeAt(Clock, TimeZone) hides the kotlinx-datetime bridge internally.
        val hour = localTimeAt(deps.clock, deps.timeZone.current()).hour
        when {
            hour < morningEnd -> "Good morning"
            hour < afternoonEnd -> "Good afternoon"
            else -> "Good evening"
        }
    }.getOrElse { "" }

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
                    scope.launch { updateSelection(selectedIds(), toggled = taskId) }
                } else {
                    scope.launch { emit(AgendaUiEvent.NavigateToTask(taskId)) }
                }
            }

            is AgendaIntent.TaskCheckClicked -> with(intent) {
                scope.launch { handleTaskCheckClicked(taskId) }
            }

            is AgendaIntent.ConfirmBlockedClose -> with(intent) {
                scope.launch {
                    val request = (currentState as? AgendaUiState.Loaded)?.pendingBlockedClose
                    if (request == null || request.taskId != taskId) return@launch
                    setBlockedClose(null)
                    deps.taskRepo.complete(taskId, force = true)
                        .onFailure { report(it, TOGGLE_COMPLETE_FAILED, "Could not update task") }
                }
            }

            is AgendaIntent.DismissBlockedClose -> setBlockedClose(null)

            is AgendaIntent.ConfirmBlockedBulkClose -> scope.launch { handleConfirmedBulkClose() }

            is AgendaIntent.DismissBlockedBulkClose -> setBlockedBulkClose(null)

            is AgendaIntent.TaskLongClicked -> with(intent) {
                if ((currentState as? AgendaUiState.Loaded)?.isSelectionMode == true) {
                    // Already in selection mode — ignore long-press, click handles selection.
                } else {
                    scope.launch { updateSelection(setOf(taskId)) }
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

            is AgendaIntent.CreateOnDates -> with(intent) {
                scope.launch { handleCreateOnDates(intent.dates) }
            }

            is AgendaIntent.EnterSelectionMode -> with(intent) {
                scope.launch { updateSelection(setOf(taskId)) }
            }

            is AgendaIntent.ToggleSelection -> with(intent) {
                scope.launch { updateSelection(selectedIds(), toggled = taskId) }
            }

            is AgendaIntent.ExitSelectionMode -> with(intent) {
                updateSelection(emptySet())
            }

            is AgendaIntent.DeleteSelected -> with(intent) {
                scope.launch { handleBulkDelete() }
            }

            is AgendaIntent.CompleteSelected -> with(intent) {
                scope.launch { handleBulkComplete() }
            }

            is AgendaIntent.MergeSelectedSeries -> with(intent) {
                scope.launch { handleMergeSeries() }
            }

            is AgendaIntent.Retry, is AgendaIntent.Reload -> {
                setState(AgendaUiState.Loading)
                _reloadTrigger.value = _reloadTrigger.value + 1
            }
        }
    }

    // ── Blocked-completion confirmation ─────────────────────────────────────────

    // The checkbox on an agenda row.
    //
    // The write decides whether a close is allowed: it refuses while a
    // prerequisite is open, and the refusal is part of the statement rather
    // than a check that runs before it. The user is asked only when the write
    // says no.
    private suspend fun handleTaskCheckClicked(taskId: TaskId) {
        val task = latestTasks.value.firstOrNull { it.id == taskId }
        if (task != null && task.completedAt == null) {
            val completed = deps.taskRepo.complete(taskId).getOrElse {
                report(it, TOGGLE_COMPLETE_FAILED, "Could not update task")
                return
            }
            if (!completed) {
                val blockers = Blocking.activeBlockers(task, latestTasks.value)
                if (blockers.isEmpty()) {
                    // The write refused but the list says nothing is in the way —
                    // the two disagree, so nothing is claimed.
                    report(
                        IllegalStateException("The completion was refused and no blocker is known"),
                        TOGGLE_COMPLETE_FAILED,
                        "Could not update task",
                    )
                    return
                }
                setBlockedClose(
                    BlockedCloseRequest(
                        taskId = task.id,
                        taskTitle = task.title,
                        blockerTitles = blockers.map { it.title }.toImmutableList(),
                    ),
                )
            }
            return
        }
        // Taking a task back into work is never gated — and it brings its
        // project back with it when that project was closed.
        deps.completion.reopen(taskId)
            .onFailure { report(it, TOGGLE_COMPLETE_FAILED, "Could not update task") }
    }

    /**
     * Publishes the confirmation for a task that cannot be closed without a
     * question — or closes it, when there is no confirmation pending.
     *
     * State, not an event: the dialog has to survive a rotation, and a one-shot
     * event is gone by the time the screen comes back.
     */
    private fun setBlockedClose(request: BlockedCloseRequest?) = updateState { current ->
        val loaded = current as? AgendaUiState.Loaded ?: return@updateState current
        loaded.copy(pendingBlockedClose = request)
    }

    /**
     * Publishes the confirmation for a selection that cannot be closed without a
     * question — or closes it, when there is no confirmation pending.
     *
     * The bulk counterpart of [setBlockedClose]: the same question, asked about
     * a selection. It is state, not an event, for the same reason.
     */
    private fun setBlockedBulkClose(request: BlockedBulkCloseRequest?) = updateState { current ->
        val loaded = current as? AgendaUiState.Loaded ?: return@updateState current
        loaded.copy(pendingBlockedBulkClose = request)
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

    /**
     * Write the selection and derive the mode from it.
     *
     * One function for the three selection gestures, because the flag and the set
     * are one thing: the mode is on exactly while something is selected. Three
     * handlers each remembered to set both, and the third one that did not would
     * have left a selection the action row could not act on.
     *
     * @param ids The selection to write.
     * @param toggled A task to add to [ids] or take out of it, when the gesture is
     *   a tap on one row rather than a wholesale replacement. Deselecting the last
     *   task leaves selection mode, because the action row and the per-row
     *   checkbox are both driven by that flag.
     */
    private fun updateSelection(ids: Set<TaskId>, toggled: TaskId? = null) = updateState { current ->
        val loaded = current as? AgendaUiState.Loaded ?: return@updateState current
        val next = when {
            toggled == null -> ids
            toggled in ids -> ids - toggled
            else -> ids + toggled
        }
        loaded.copy(
            isSelectionMode = next.isNotEmpty(),
            selectedTaskIds = next.toImmutableSet(),
        )
    }

    /**
     * Merges the two series the selection spans.
     *
     * The selection has to span exactly two series: one series is already one
     * piece of work, and three series is a question this action does not answer.
     * The series of the first selected row keeps the identity — its id and what
     * it says about the work — and the other one joins it.
     */
    private suspend fun handleMergeSeries() {
        val ids = selectedIds()
        if (ids.isEmpty()) return

        val seriesIds = selectedSeriesIds(ids)
        if (seriesIds.size != 2) {
            emit(
                AgendaUiEvent.BulkOperationDone(
                    count = 0,
                    operation = "merged",
                    error = "Выберите две серии, чтобы объединить их",
                ),
            )
            return
        }

        val result = deps.mergeSeries(seriesIds.first(), seriesIds.last())
        updateSelection(emptySet())

        result.fold(
            onSuccess = {
                emit(AgendaUiEvent.BulkOperationDone(count = ids.size, operation = "merged"))
            },
            onFailure = { error ->
                crashReporter.report(error, BULK_MERGE_FAILED)
                emit(
                    AgendaUiEvent.BulkOperationDone(
                        count = 0,
                        operation = "merged",
                        error = error.message,
                    ),
                )
            },
        )
    }

    /** The series the selected rows belong to, in the order they were selected. */
    private fun selectedSeriesIds(ids: Set<TaskId>): List<TaskSeriesId> {
        val rows = (currentState as? AgendaUiState.Loaded)
            ?.sections
            ?.flatMap { it.tasks }
            .orEmpty()
        return ids.mapNotNull { id -> rows.firstOrNull { it.task.id == id }?.task?.seriesId }.distinct()
    }

    /**
     * Delete all selected tasks via soft-delete, then offer a single Undo that
     * restores every one of them. Exits selection mode on completion.
     */
    private suspend fun handleBulkDelete() {
        val ids = selectedIds().toList()
        if (ids.isEmpty()) return

        val result = deps.taskMutations.bulkDelete(ids)
        if (result.isFailure) {
            crashReporter.report(
                result.exceptionOrNull() ?: IllegalStateException("bulk delete failed"),
                BULK_DELETE_FAILED,
            )
            emit(
                AgendaUiEvent.BulkOperationDone(
                    count = 0,
                    operation = "deleted",
                    error = result.exceptionOrNull()?.message,
                ),
            )
            return
        }

        updateSelection(emptySet())

        // Offer a single undo covering all deleted tasks.
        val generation = undoSlot.incrementAndFetch()
        _pendingDelete.value = PendingDelete(ids, "${ids.size} task${if (ids.size != 1) "s" else ""}")
        countdown.start { undoSlot.load() == generation }
    }

    /**
     * Complete all selected tasks via [TaskMutationsUseCase.bulkComplete].
     * Exits selection mode on completion.
     */
    private suspend fun handleBulkComplete() {
        val ids = selectedIds().toList()
        if (ids.isEmpty()) return

        val result = deps.taskMutations.bulkComplete(ids)
        result.fold(
            onSuccess = {
                updateSelection(emptySet())
                emit(AgendaUiEvent.BulkOperationDone(count = ids.size, operation = "completed"))
            },
            onFailure = { error ->
                if (error is BlockedTasksException) {
                    // The same question the single close asks, asked about the
                    // selection. Nothing was written, so the selection stays
                    // where it was and the user's answer decides.
                    val blocked = error.blockedTaskIds.toList()
                    setBlockedBulkClose(
                        BlockedBulkCloseRequest(
                            taskIds = ids,
                            blockedTaskIds = blocked,
                            blockedTaskTitles = blocked.mapNotNull { id ->
                                latestTasks.value.firstOrNull { it.id == id }?.title
                            }.toImmutableList(),
                        ),
                    )
                } else {
                    crashReporter.report(error, BULK_COMPLETE_FAILED)
                    updateSelection(emptySet())
                    emit(AgendaUiEvent.BulkOperationDone(count = 0, operation = "completed", error = error.message))
                }
            },
        )
    }

    /**
     * Closes the selection after the user confirmed it, prerequisites and all.
     *
     * The confirmed path is the only one that writes: the refusal left the
     * selection untouched, and the answer says the user meant it.
     */
    private suspend fun handleConfirmedBulkClose() {
        val request = (currentState as? AgendaUiState.Loaded)?.pendingBlockedBulkClose ?: return
        setBlockedBulkClose(null)
        val ids = request.taskIds
        if (ids.isEmpty()) return
        deps.taskMutations.bulkComplete(ids, force = true)
            .onSuccess {
                updateSelection(emptySet())
                emit(AgendaUiEvent.BulkOperationDone(count = ids.size, operation = "completed"))
            }
            .onFailure { error ->
                crashReporter.report(error, BULK_COMPLETE_FAILED)
                emit(AgendaUiEvent.BulkOperationDone(count = 0, operation = "completed", error = error.message))
            }
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
            _pendingDelete.value = PendingDelete(listOf(taskId), taskTitle)
            countdown.start { undoSlot.load() == generation }
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
        var failed = false
        pending.taskIds.forEach { id ->
            deps.taskRepo.restore(id)
                .onFailure {
                    failed = true
                    report(it, RESTORE_FAILED, "Could not restore")
                }
        }
        if (!failed) {
            countdown.cancel()
            _pendingDelete.value = null
        }
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
            // The prefill's title comes from outside the field, so the field never produced it.
            title = FieldOwnedText.empty().seeded(sectionPrefill.title ?: ""),
            dueDate = resolvedDue?.let { DueDateOption.Custom(it, it.toString()) }
                ?: DueDateOption.None,
        )

        // Save to DraftStore under the section's key. The key is built in one
        // place — TaskDraftKeys — so the navigator that points the editor at it
        // cannot name a different draft.
        val key = TaskDraftKeys.agendaSection(sectionId)
        deps.draftStore.save(key, draft, TaskDraft.serializer())

        emit(AgendaUiEvent.CreateInSection(sectionId))
    }

    /**
     * Writes the picked days into a draft and asks for the editor.
     *
     * The draft is the channel: the agenda does not create the task, and the
     * editor does not know where the days came from. Writing a draft and
     * pointing the editor at it means the task is created by exactly the same
     * code as a task created from the calendar.
     */
    private suspend fun handleCreateOnDates(dates: Set<kotlinx.datetime.LocalDate>) {
        val sorted = dates.sorted()
        if (sorted.isEmpty()) return
        val primary = sorted.first()
        val draft = TaskDraft(
            dueDate = DueDateOption.Custom(primary, primary.toString()),
            extraDueDates = sorted.drop(1),
        )
        val key = TaskDraftKeys.AGENDA_MULTI_DATE
        deps.draftStore.save(key, draft, TaskDraft.serializer())
        emit(AgendaUiEvent.OpenCreateWithDates(key))
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
        private const val BULK_MERGE_FAILED = "agenda.bulk_merge_failed"
        private const val BULK_COMPLETE_FAILED = "agenda.bulk_complete_failed"
    }
}

/**
 * One or more tasks that have been soft-deleted and are pending an undo window.
 *
 * @param taskIds The deleted task ids (one for a single delete, many for a bulk delete).
 * @param label Short label for the snackbar.
 */
data class PendingDelete(val taskIds: List<TaskId>, val label: String)
