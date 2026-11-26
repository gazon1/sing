package com.singularity.todo.core.ui

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * MVI base for editor/draft screens. Encapsulates:
 * - A [draft] value (backed by [DraftState]) mutated via [updateDraft]
 * - Debounced silent autosave (no [Saved][DraftUiEvent.Saved] events from autosave)
 * - Explicit [save] with validation + [persist] + checkpoint + [onSaved] callback
 * - Entity switching via [open]
 * - Async effects on the draft via [launchDraftEffect]
 *
 * ## Autosave is silent
 * Autosave failures call [onAutosaveError] only — they do NOT emit [DraftUiEvent.Saved],
 * and they do NOT move the [DraftState] checkpoint. Only explicit [save] does both.
 * This prevents the "Saved-spam" regression where every keystroke in a debounce
 * window fires a notification, and keeps [DraftUiState.isDirty] honest.
 *
 * ## Single source of truth for state
 * All of `draft`, `isSaving`, `error`, and `isDirty` live in one [MutableStateFlow]
 * of [DraftUiState], updated atomically via [MutableStateFlow.update]. There is no
 * separate `combine` step re-deriving state from multiple flows — that
 * intermediate design allowed a window where the UI could observe a new draft
 * paired with a stale error flag. Live validation is a pure function of the
 * draft computed inline on every update; [DraftUiState.error] is reserved for
 * persistence-layer failures (autosave/save), which is the only kind of error
 * that needs to survive across an `updateDraft` call.
 *
 * **The draft value is owned by [draftState]; [DraftUiState.draft] is its published
 * snapshot.** Exactly one function writes the snapshot — [pushUiState] — and every
 * mutation ([updateDraft], [open], [discard], restore, [save]) ends with it. The copy
 * is what makes a snapshot atomic for the UI; deriving it from two flows would
 * reintroduce the stale-pairing window above. See
 * `2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md`.
 *
 * ## What a subclass may override
 * Six seams, each with a production consumer: [validate] and [persist] (abstract — the
 * editor's own logic), and [onSaved] / [onAutosaved] / [onAutosaveError] / [clearAutosave]
 * for reporting and storage side effects. The mutation path itself ([updateDraft], [open],
 * [discard], [dismissError], [launchDraftEffect], [save]) is **final**: an overridable
 * writer can skip [pushUiState] and leave the snapshot describing a draft the ViewModel no
 * longer holds — the same bypass that made [save] non-`open`.
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
 *     override suspend fun clearAutosave() {
 *         deps.draftStore.clear(DRAFT_KEY)
 *     }
 *
 *     override suspend fun onSaved(saved: TaskDraft): TaskDraft? {
 *         emit(TaskCreateUiEvent.Saved)
 *         return null
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
 *   Called silently; failures go to [onAutosaveError] only; never moves the checkpoint.
 * @param restore Called once on init, *before* the autosave loop starts, to restore
 *   a previously saved draft. Return null if no draft was saved — [initialDraft] is used.
 *   Running this to completion before autosave starts (rather than racing the two
 *   in parallel) means a slow restore can no longer lose to the user's first
 *   keystroke: there is no window where typing beats restore and silently
 *   discards it.
 * @param logger For [onAutosaveError] logging.
 * @param autosaveDebounceMs Milliseconds to wait after the last edit before autosaving.
 * @param scope Coroutine scope for collectors and async work.
 */
@OptIn(FlowPreview::class)
abstract class DraftMviViewModel<D : Any, I : MviIntent, E : MviEvent>(
    private val initialDraft: D,
    private val autosave: suspend (D) -> Unit,
    private val restore: suspend () -> D? = { null },
    private val logger: Logger,
    private val autosaveDebounceMs: Long = 500L,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<DraftUiState<D>, I, E>(
        initialState = DraftUiState(draft = initialDraft),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    private val draftState = DraftState(initialDraft)

    /**
     * In-flight draft effects, keyed by the [launchDraftEffect] key that started them.
     *
     * A plain map because this is touched only from [launchDraftEffect], which the UI
     * calls on the main thread and which reads, replaces and cancels synchronously
     * before returning — the coroutines it stores are *launched* elsewhere, but the
     * map is not shared with them. `ConcurrentHashMap` said otherwise, and said it
     * about a class that does not exist on Kotlin/Native.
     */
    private val effectJobs = mutableMapOf<Any, Job>()

    /**
     * Serialises the two writers: the debounced autosave and the explicit [save].
     *
     * They write the same entity, and they are started independently — the editor stays
     * editable while a save suspends, and the debounce fires on its own schedule. Without
     * this, a save that captured draft A can still be in flight when the autosave writes
     * the newer B, and A then lands on top of B: the store keeps the *older* text, the
     * user sees the newer text on screen and reads "dirty", and a process death loses it.
     *
     * The lock is what makes the later write the last write. It does not make a write
     * cancellable — see [save] on what is still not cancelled.
     */
    private val writeLock = Mutex()

    /** Current draft value. Use inside [updateDraft] transforms. */
    protected val draft: D get() = draftState.value

    init {
        vmScope.launch {
            // 1. Restore persisted draft first — seed-if-empty, run to completion
            //    before autosave starts so a slow restore can't lose a race to the
            //    user's first keystroke (see `restore` kdoc above).
            val restored = restore()
            if (restored != null && draftState.value == initialDraft && !draftState.isDirty) {
                draftState.open(restored)
            }

            // 2. First evaluation of the UI state. It cannot happen in the
            //    initial-state expression above because validate() is an open
            //    member: calling it from this class's constructor would run
            //    before a subclass's own properties are initialized. Running it
            //    from the launched coroutine happens after construction, so
            //    implementations may safely read their own state. Validation
            //    errors stay out of this first pass — they belong to user edits,
            //    not to the draft the editor was opened with.
            pushUiState(includeValidationError = false)

            // 3. Debounced silent autosave loop — starts only after restore settles.
            //
            // The catches here are [runCatchingCancellable]'s and [Exception]-bounded on
            // purpose: cancellation propagates, a fatal `Error` propagates to vmScope's
            // handler, and everything an autosave or a callback can throw ordinarily ends
            // up in [onAutosaveError]. A callback that threw *outside* the guard would end
            // this collector and take autosave with it for the rest of the editor's life —
            // silently, since the editor would look alive while nothing was being stored.
            draftState.current
                .drop(1)
                .debounce(autosaveDebounceMs.milliseconds)
                .collect { current ->
                    runCatchingCancellable { writeLock.withLock { autosave(current) } }
                        .fold(
                            onSuccess = {
                                runCatchingCancellable { onAutosaved(current) }
                                    .onFailure { onAutosaveError(it) }
                            },
                            onFailure = { onAutosaveError(it) },
                        )
                }
        }
    }

    /**
     * Recomputes [DraftUiState] from [draftState] + the current saving flag and publishes it.
     *
     * @param includeValidationError Whether a live validation failure should be
     *   surfaced in [DraftUiState.error]. False for the initial pass, which only
     *   needs [DraftUiState.isSaveEnabled] / [DraftUiState.isDirty].
     */
    private fun pushUiState(includeValidationError: Boolean = true) {
        val current = draftState.value
        val validationError = safeValidate(current)
        updateState { state ->
            state.copy(
                draft = current,
                isSaveEnabled = validationError == null && !state.isSaving,
                // Recomputed on every edit so a validation error disappears as
                // soon as the draft becomes valid. A persistence error set by
                // [save] is cleared the same way — the user has moved on.
                error = if (includeValidationError) validationError else state.error,
                isDirty = draftState.isDirty,
            )
        }
    }

    /**
     * Mutates the draft. Recomputes validation and dirty state atomically —
     * there is no window where the UI can observe the new draft paired with a
     * stale error flag.
     */
    fun updateDraft(transform: (D) -> D) {
        draftState.edit(transform)
        pushUiState()
    }

    /**
     * Switches to a new entity (e.g. user opened a different note in the same VM).
     * Cancels any in-flight autosave for the old entity.
     * Caller is responsible for checking [isDirty] and prompting save if needed.
     */
    fun open(newDraft: D) {
        if (sameEntity(draftState.value, newDraft)) return
        draftState.open(newDraft)
        updateState { it.copy(isSaving = false, error = null) }
        pushUiState()
    }

    /**
     * Whether [incoming] is the entity the editor already holds, which makes [open] a
     * no-op.
     *
     * **Private, not an extension point.** It was `protected open` and promised as the
     * seam for draft types that wrap an entity id — the repo has none. Both real editors
     * ([TaskDraft] carries the scope in its draft, `Editing` carries the note id) want
     * "a different draft is a different open", which is plain equality. An override was
     * also unenforceable where it mattered: [open] calls it *before* the draft moves, so
     * a wrong implementation wedges the editor on the first entity, and no caller can
     * observe the difference until then. Restore it as a seam when a draft type exists
     * whose two *edited* copies of one entity must not reset each other.
     */
    private fun sameEntity(
        current: D,
        incoming: D,
    ): Boolean = current == incoming

    /** Override to return a human-readable validation error, or null if valid. */
    protected abstract fun validate(draft: D): String?

    /**
     * [validate] as the base class calls it: an implementation that throws
     * degrades to a validation error instead of escaping.
     *
     * `validate` is invoked from two places — from [pushUiState] on every
     * keystroke, and from `save()`. Both are reachable from `onIntent`, which the
     * UI calls directly, so a throwing validator used to propagate out of a text
     * change and take the composition down rather than mark the draft invalid.
     *
     * The catch is [Exception], matching [runCatchingCancellable]: a validator has no
     * business failing fatally, and turning an `Error` into a snackbar would leave the
     * user to "fix" the draft against a failure no edit can repair.
     */
    @Suppress("TooGenericExceptionCaught") // the point is to catch *anything* an open fun throws
    private fun safeValidate(draft: D): String? =
        try {
            validate(draft)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "validate failed: ${e.toMessage()}" }
            e.toMessage("Validation failed")
        }

    /** Persist the draft. Return [Either.Right] on success, [Either.Left] on failure. */
    protected abstract suspend fun persist(draft: D): Either<AppError, Unit>

    /**
     * Called after a successful explicit [save], with the draft that was persisted.
     *
     * **Return the draft the save should be compared against, or null to leave the
     * checkpoint on [saved] as it is.** A subclass that adjusts the draft here — NoteEditor
     * clears its own dirty flag, because the text is on disk — returns the adjusted value,
     * computed from `saved`. It must **not** read the live draft for that: the editor keeps
     * accepting keystrokes while this hook suspends, and a keystroke that lands in that
     * window is unsaved text, not part of what the save stored.
     *
     * The return value is the whole guard against the subtle bug this replaced: taking the
     * checkpoint from the live draft after the hook blessed whatever the user typed
     * meanwhile, `isDirty` went false with text no store held, and the back guard stopped
     * asking before dropping it.
     */
    protected open suspend fun onSaved(saved: D): D? = null

    /**
     * Called once, right after a successful explicit [save], to clear whatever
     * [autosave] persisted (e.g. delete the draft-store entry). Default is a
     * no-op, which means the autosave store and the "real" store are expected
     * to be the same target — if they're not, override this, otherwise a
     * stale autosave entry for an already-saved entity will linger and get
     * restored by [restore] the next time this editor opens.
     */
    protected open suspend fun clearAutosave() {}

    /**
     * Called when autosave fails. Default logs the error.
     * Override to surface DB/network failures to the UI (NoteEditor requires this).
     */
    protected open fun onAutosaveError(e: Throwable) {
        logger.e(e) { "autosave failed: ${e.toMessage()}" }
        crashReporter.report(e, "draft.autosave_failed")
    }

    /**
     * Called after a successful autosave. The [DraftState] checkpoint is
     * intentionally NOT moved here: [discard] must keep reverting to the last
     * *explicitly saved* state, not the last autosaved one. Override to clear
     * draft-local dirty/new flags (e.g. NoteEditor's `Editing.isDirty` field).
     */
    protected open fun onAutosaved(current: D) {}

    /**
     * Explicit save: validates, persists, checkpoints the draft, clears
     * autosave storage, calls [onSaved].
     * Guarded against concurrent calls (race condition prevention).
     *
     * ## Which failures become a snackbar and which escape
     * Ordinary failures — a refused [persist], a `Left`, a thrown [Exception], a
     * rejected draft — become [DraftUiState.error] and the draft is left untouched, so
     * the user's text survives a failed save. A fatal [Error] (OOM, StackOverflow) is
     * **not** caught here: it propagates out of this coroutine to [vmScope]'s
     * `CoroutineExceptionHandler`, which reports it as `background.coroutine_failed` and
     * does not kill the process. Swallowing it instead would show the user a snackbar
     * about a failure no edit can repair. This `finally` still re-enables the button.
     * See `2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md`.
     *
     * **Not `open`, deliberately.** The failure handling below — the `catch`
     * that turns a thrown [persist] into a visible error — is the whole point of
     * this method, and an overridable one can be bypassed: a subclass that
     * reimplemented `save()` would silently reintroduce the bug that
     * `2026-09-30-draft-save-failure-and-testtag-honesty.md` fixed, because the
     * protection lives in *this* body rather than in the type. An implementation
     * that needs different behaviour overrides [persist] or [validate], which
     * are still open and are the intended extension points.
     */
    @Suppress("TooGenericExceptionCaught") // a save must not lose the user's work silently
    fun save() {
        if (currentState.isSaving) return
        updateState { it.copy(isSaving = true) }
        vmScope.launch {
            try {
                val currentDraft = draftState.value
                val validationError = safeValidate(currentDraft)
                if (validationError != null) {
                    // Logged, like the throw arm below. Only the throw arm used to
                    // write anything, so a draft rejected by validation or refused by
                    // persist left no trace outside the screen — and the screen's only
                    // signal is a snackbar that is gone within seconds. That is why
                    // CreateTaskFlowTest's save failure took six Gradle runs to place:
                    // the evidence the harness captured had nothing in it.
                    logger.e { "save rejected by validation: $validationError" }
                    updateState { it.copy(error = validationError, isSaving = false) }
                    return@launch
                }
                // Under the same lock as the autosave: the two write the same entity, and
                // an in-flight save must not be overtaken by — or overtake — a newer write.
                when (val result = writeLock.withLock { persist(currentDraft) }) {
                    is Either.Left -> {
                        val message = result.error.toMessage("Save failed")
                        // With the cause, so the log carries the stack and not just the
                        // message. A `Left` that arrives without one is indistinguishable
                        // from a plain refusal once it has crossed the use case boundary.
                        logger.e(result.error.cause) { "save refused: $message" }
                        updateState { it.copy(error = message) }
                    }

                    is Either.Right -> {
                        // The checkpoint is the draft that was *written*, not whatever the
                        // draft holds now: the editor stays editable through every suspension
                        // below, so a keystroke can arrive at any of them, and blessing it as
                        // saved would leave `isDirty` false over text no store holds.
                        draftState.checkpoint(currentDraft)
                        pushUiState()
                        clearAutosave()
                        // The hook tells us what to compare against, and can only speak
                        // about the draft it was given — never about the live one. That is
                        // how NoteEditor's own dirty-flag adjustment is still honoured while
                        // a keystroke during the same hook stays unsaved
                        // (`2026-11-09-the-saved-draft-is-the-one-the-save-left.md`).
                        val asSaved = onSaved(currentDraft)
                        if (asSaved != null) {
                            draftState.checkpoint(asSaved)
                            pushUiState()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `validate` and `persist` are open functions an implementation
                // may satisfy by throwing rather than by returning Either.Left.
                // Without this the throw cancelled the save coroutine, `finally`
                // re-enabled the button, and the save failed silently — no error
                // state, no snackbar, no log. That is how a Compose UI test for
                // the task editor presented: every assertion on the editor was
                // green and the saved task simply never appeared.
                //
                // Bounded at [Exception] on purpose: a fatal `Error` is not a save
                // failure the user could act on, and the kdoc's policy is that it
                // escapes to vmScope's handler instead of becoming a snackbar.
                logger.e(e) { "save failed: ${e.toMessage()}" }
                crashReporter.report(e, "draft.save_failed")
                updateState { it.copy(error = e.toMessage("Save failed")) }
            } finally {
                updateState { it.copy(isSaving = false) }
            }
        }
    }

    /**
     * Discards changes and reverts the draft to the last checkpoint (the last
     * explicit [save], or the restored/initial draft if never saved).
     *
     * Does NOT clear [autosave] storage: the autosave entry is a standing
     * safety net, not part of this editor's visible undo. If you want a hard
     * reset that also forgets the autosaved copy, call [clearAutosave]
     * explicitly after [discard].
     */
    fun discard() {
        draftState.discard()
        updateState { it.copy(isSaving = false, error = null) }
        pushUiState()
    }

    /** Dismisses the current error banner. */
    fun dismissError() {
        updateState { it.copy(error = null) }
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
    fun <R> launchDraftEffect(
        key: Any,
        operation: suspend (current: D) -> R,
        onResult: (current: D, result: R) -> D?,
        onEvent: (result: R) -> E? = { null },
    ) {
        effectJobs
            .remove(key)
            ?.cancel()
        effectJobs[key] =
            vmScope.launch {
                val before = draftState.value
                val result = operation(before)
                onResult(before, result)?.let { updateDraft { _ -> it } }
                onEvent(result)?.let { emit(it) }
            }
    }
}

/** UI state emitted by [DraftMviViewModel]. */
data class DraftUiState<D>(
    val draft: D,
    val isSaveEnabled: Boolean = false,
    val error: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
)
