package com.singularity.todo.core.ui

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.Either.Left
import com.singularity.todo.core.error.Either.Right
import com.singularity.todo.core.error.toMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update

/**
 * MVI base for editor/draft screens. Encapsulates:
 * - A [draft] [MutableStateFlow] mutated via [updateDraft]
 * - Debounced silent autosave (no [Saved][DraftUiEvent.Saved] events from autosave)
 * - Explicit [save] with validation + [persist] + [onSaved] callback
 * - Entity switching via [open]
 * - Async effects on the draft via [launchDraftEffect]
 *
 * ## Autosave is silent
 * Autosave failures call [onAutosaveError] only — they do NOT emit [DraftUiEvent.Saved].
 * Only explicit [save] emits [DraftUiEvent.Saved]. This prevents the "Saved-spam"
 * regression where every keystroke in a debounce window fires a notification.
 *
 * ## Usage
 * ```
 * class TaskCreateViewModel(
 *     deps: TaskCreateDeps,
 *     initialDueDate: LocalDate?,
 *     scope: AutoCloseableCoroutineScope,
 * ) : DraftMviViewModel<TaskDraft, TaskCreateIntent, TaskCreateUiEvent>(
 *     initialDraft = TaskDraft(dueDate = initialDueDate?.toOption()),
 *     autosave = { draft -> deps.draftStore.save(DRAFT_KEY, draft, TaskDraft.serializer()) },
 *     restore = { deps.draftStore.load(DRAFT_KEY, TaskDraft.serializer()) },
 *     logger = Logger.withTag("TaskCreate"),
 *     scope = scope,
 * ) {
 *     override fun validate(draft: TaskDraft): String? =
 *         if (draft.title.isBlank()) "Title is required" else null
 *
 *     override suspend fun persist(draft: TaskDraft): Either<AppError, Unit> =
 *         deps.createFromDraft(draft)
 *
 *     override suspend fun onSaved() {
 *         emit(TaskCreateUiEvent.Saved)
 *     }
 * }
 * ```
 *
 * @param D Draft type (the entity being edited, e.g. [TaskDraft]).
 * @param I Intent type extending [MviIntent].
 * @param E Event type extending [MviEvent].
 * @param initialDraft The starting draft when the editor opens fresh (no restore).
 * @param autosave Called after each debounce window with the current draft.
 *   Must be idempotent — calling it multiple times with the same draft is safe.
 *   Called silently; failures go to [onAutosaveError] only.
 * @param restore Called once on init to restore a previously saved draft.
 *   Return null if no draft was saved — [initialDraft] is used.
 * @param logger For [onAutosaveError] logging.
 * @param autosaveDebounceMs Milliseconds to wait after the last edit before autosaving.
 * @param scope Coroutine scope for collectors and async work.
 */
@Immutable
abstract class DraftMviViewModel<D : Any, I : MviIntent, E : MviEvent>(
    private val initialDraft: D,
    private val autosave: suspend (D) -> Unit,
    private val restore: suspend () -> D? = { null },
    private val logger: Logger,
    autosaveDebounceMs: Long = 500L,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<DraftUiState<D>, I, E>(
    initialState = DraftUiState(draft = initialDraft),
    scope = scope,
) {
    protected val vmScope: AutoCloseableCoroutineScope = scope

    private var baseline: D = initialDraft
    private val _draft = MutableStateFlow(initialDraft)
    private val _isSaving = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    /** Current draft value. Use inside [updateDraft] transforms. */
    protected val draft: D get() = _draft.value

    init {
        // 1. Restore persisted draft if present — seed-if-empty pattern
        vmScope.launch {
            restore()?.let { restored ->
                if (_draft.value == baseline) {
                    _draft.value = restored
                    baseline = restored
                }
            }
        }
        // 2. Debounced silent autosave loop
        vmScope.launch {
            _draft.drop(1)
                .debounce { autosaveDebounceMs }
                .collect { current ->
                    runCatching { autosave(current) }
                        .onFailure { onAutosaveError(it) }
                }
        }
        // 3. Derive DraftUiState from draft + saving + error flows
        vmScope.launch {
            combine(_draft, _isSaving, _error) { draft, saving, error ->
                DraftUiState(
                    draft = draft,
                    isSaveEnabled = validate(draft) == null && !saving,
                    error = error,
                    isDirty = draft != baseline,
                    isSaving = saving,
                )
            }.collect { newState ->
                updateState { newState }
            }
        }
    }

    /**
     * Mutates the draft. Sets error if the new draft is invalid;
     * clears error when transitioning from invalid to valid.
     */
    public open fun updateDraft(transform: (D) -> D) {
        _draft.update(transform)
        val validationError = validate(_draft.value)
        if (validationError != null) {
            if (_error.value == null) _error.value = validationError
        } else if (_error.value != null) {
            _error.value = null
        }
    }

    /**
     * Switches to a new entity (e.g. user opened a different note in the same VM).
     * Cancels any in-flight autosave for the old entity.
     * Caller is responsible for checking [isDirty] and prompting save if needed.
     */
    public open fun open(newDraft: D) {
        if (sameEntity(_draft.value, newDraft)) return
        cancelPendingAutosave()
        baseline = newDraft
        _draft.value = newDraft
        _isSaving.value = false
        _error.value = null
    }

    /**
     * Compare two drafts to determine if they refer to the same entity.
     * Default is identity equality. Override for draft types that wrap an entity ID.
     * Example: `current.id == incoming.id` for a NoteDraft that wraps a [Note].
     */
    protected open fun sameEntity(current: D, incoming: D): Boolean = current == incoming

    /** Override to return a human-readable validation error, or null if valid. */
    protected abstract fun validate(draft: D): String?

    /** Persist the draft. Return [Either.Right] on success, [Either.Left] on failure. */
    protected abstract suspend fun persist(draft: D): Either<AppError, Unit>

    /** Called after a successful explicit [save]. Default is a no-op. */
    protected open suspend fun onSaved() {}

    /**
     * Called when autosave fails. Default logs the error.
     * Override to surface DB/network failures to the UI (NoteEditor requires this).
     */
    protected open fun onAutosaveError(e: Throwable) {
        logger.e(e) { "autosave failed: ${e.toMessage()}" }
    }

    /**
     * Explicit save: validates, persists, clears draft storage, calls [onSaved].
     * Guarded against concurrent calls (race condition prevention).
     */
    public open fun save() {
        if (!_isSaving.compareAndSet(expect = false, update = true)) return
        vmScope.launch {
            try {
                val currentDraft = _draft.value
                val validationError = validate(currentDraft)
                if (validationError != null) {
                    _error.value = validationError
                    _isSaving.value = false
                    return@launch
                }
                when (val result = persist(currentDraft)) {
                    is Either.Left -> _error.value = result.error.toMessage("Save failed")
                    is Either.Right -> onSaved()
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** Discards changes and resets to [baseline]. Does NOT clear persisted draft storage. */
    public open fun discard() {
        _draft.value = baseline
        _isSaving.value = false
        _error.value = null
    }

    /** Dismisses the current error banner. */
    public open fun dismissError() {
        _error.value = null
    }

    /**
     * Launches an async side-effect on the current draft (e.g. AI refinement).
     * Re-tapping with the same [key] cancels the prior in-flight operation.
     *
     * @param key Deduplication key — typically a string constant like `"ai-improve"`.
     * @param operation Suspend function receiving the current draft, returning a result.
     * @param onResult Called with the current draft and result. Return a new draft to
     *   update the editor, or null to leave the draft unchanged.
     * @param onEvent Optional — return an event to emit after a successful result.
     */
    public open fun <R> launchDraftEffect(
        key: Any,
        operation: suspend (current: D) -> R,
        onResult: (current: D, result: R) -> D?,
        onEvent: (result: R) -> E? = { null },
    ) {
        cancelEffect(key)
        effectJobs[key] = vmScope.launch {
            val before = _draft.value
            val result = operation(before)
            onResult(before, result)?.let { updateDraft { _ -> it } }
            onEvent(result)?.let { emit(it) }
        }
    }

    private val effectJobs = mutableMapOf<Any, Job>()

    private fun cancelEffect(key: Any) {
        effectJobs.remove(key)?.cancel()
    }

    private var pendingAutosaveJob: Job? = null

    private fun cancelPendingAutosave() {
        pendingAutosaveJob?.cancel()
        pendingAutosaveJob = null
    }
}

/** UI state emitted by [DraftMviViewModel]. */
@Immutable
data class DraftUiState<D>(
    val draft: D,
    val isSaveEnabled: Boolean = false,
    val error: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
)
