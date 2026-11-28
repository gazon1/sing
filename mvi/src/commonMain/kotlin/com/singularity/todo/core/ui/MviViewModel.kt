package com.singularity.todo.core.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base ViewModel for the MVI loop: a single [StateFlow] of UI state, a one-shot
 * [EventBus] for effects the screen must act on exactly once, and an [onIntent] entry point.
 *
 * ## State has exactly two entry points
 * [updateState] (apply a reducer, CAS) and [setState] (direct replacement). Both are
 * `final` on purpose: an `open` writer that a subclass can silently override is how
 * the state stream and the event bus drift apart.
 *
 * ## State is observed read-only
 * [stateFlow] is the canonical observation API and [state] is the same read-only flow
 * under a shorter name. Neither exposes a [MutableStateFlow]: a public mutable handle
 * lets any caller write state behind the ViewModel's back, and then the screen shows
 * something the ViewModel never decided. The backing flow itself is private, so a
 * subclass cannot reach around [updateState] / [setState] either.
 *
 * ## Errors
 * [catchTo] is the primitive for "run this, route a failure somewhere"; [emitError] is
 * the common case where a failure becomes a one-shot event. [catchTo]'s [onError] is
 * `suspend` so callers can [emit] straight from it — the non-suspend variants forced
 * every call site to wrap the emit in a nested `launch`.
 *
 * ## Scope ownership is a contract, and only half of it is machine-checked
 * [scope] is required and the ViewModel owns it: the caller creates it for this instance,
 * and this class cancels its `Job` when the ViewModel is cleared.
 *
 * **What is checked.** [scope] must carry a `Job`. A context without one would make teardown
 * a silent no-op and leak every coroutine the ViewModel launched, so a missing `Job` is a
 * construction error — the one part of this contract a constructor can enforce.
 *
 * **What is not checked, and cannot be.** Whether the scope is *exclusive to this instance*.
 * Two ViewModels handed the same `CoroutineScope` look identical from here, and clearing one
 * cancels the other's work:
 *
 * ```
 * val shared = CoroutineScope(SupervisorJob())
 * val first = FirstViewModel(scope = shared)   // constructing these is legal
 * val second = SecondViewModel(scope = shared) // clearing `first` cancels `second`
 * ```
 *
 * No heuristic can tell that apart from a correct parent/child pair, and a library that guessed
 * would fail the legitimate case to catch the wrong one. So the rule is a contract on the
 * caller, not a validation: **create one scope per ViewModel instance.** A composition root
 * that builds scopes is where this belongs, and `:mvi` does not own that decision — which is
 * also why the module publishes no scope factory.
 *
 * **A child scope is parented explicitly.** For a scope that has to be tied to a parent, pass
 * `SupervisorJob(parent.coroutineContext[Job])` in the context. `CoroutineContext +
 * SupervisorJob()` with no parent argument *replaces* the context's `Job` with an independent
 * one, which is not a child scope — and the ViewModel would cancel a `Job` nothing else owns.
 *
 * ## Usage
 * ```
 * class TagsViewModel(
 *     private val tagRepo: TagsRepository,
 *     diagnostics: MviDiagnostics,
 *     scope: CoroutineScope,
 * ) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
 *     initialState = TagsUiState.Loading,
 *     diagnostics = diagnostics,
 *     scope = scope,
 * ) {
 *     // no addCloseable(scope) needed here — the base initializer does it
 *
 *     override fun onIntent(intent: TagsIntent) {
 *         when (intent) {
 *             is TagsIntent.Delete -> emitError("Delete failed", TagsUiEvent::ShowError) {
 *                 tagRepo.delete(intent.id)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param S The UI state type (sealed hierarchy recommended).
 * @param I The intent type (user action sealed hierarchy).
 * @param E The event type (one-shot UI events sealed hierarchy).
 * @param initialState The initial UI state.
 * @param diagnostics Where a handled failure is classified, reported and logged. Defaults to
 *   [NoOpMviDiagnostics], so a ViewModel built in a test is silent and nothing reaches for a
 *   global — but a ViewModel that handles real errors should pass the application's adapter.
 * @param scope The ViewModel-owned coroutine scope: it must carry a [Job] in its context, and
 *   this class cancels that [Job] on teardown. See "Scope ownership" above.
 */
abstract class MviViewModel<S, I : MviIntent, E : MviEvent>(
    initialState: S,
    private val diagnostics: MviDiagnostics = NoOpMviDiagnostics,
    scope: CoroutineScope,
) : ViewModel() {
    /**
     * The `Job` teardown cancels.
     *
     * Captured at construction, after the presence check, so teardown cannot look it up and
     * find nothing: a scope whose context is mutated afterwards still has the `Job` this
     * ViewModel is responsible for.
     */
    private val ownedJob: Job =
        requireNotNull(scope.coroutineContext[Job]) {
            "MviViewModel requires a CoroutineScope whose context carries a Job — a scope " +
                "without one cannot be cancelled at teardown, and every coroutine launched on " +
                "it would outlive the ViewModel. Build the scope with a Job, e.g. " +
                "SupervisorJob(parent.coroutineContext[Job])."
        }

    init {
        // Cancelling the owned Job *is* the teardown: a Tier-1 ViewModel needs no
        // `onCleared()` override for it, and a caller-supplied child Job keeps the parent's
        // other work intact.
        //
        // The closeable is a lambda over the owned `Job` rather than the `scope` itself: the
        // scope is a plain `CoroutineScope`, which is not `AutoCloseable`, and the `Job` is what
        // teardown has to reach. This class is exempt from `VmCloseable` for the same reason it
        // exists — it *is* the place registration happens, and a rule aimed at subclasses has
        // nothing to ask of it.
        addCloseable { ownedJob.cancel() }
    }

    /**
     * Scope for collectors and intent handlers. Deliberately not `open` — the eight
     * subclasses that overrode it only ever assigned the same `scope` back to it.
     */
    protected val vmScope: CoroutineScope = scope

    /**
     * The one writable state holder. Private on purpose: a subclass that could reach it
     * could write state without [updateState] / [setState], and the state the screen
     * shows would stop being the state the ViewModel decided.
     */
    @Suppress("VariableNaming") // _state is the canonical name for the mutable backing field
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)

    /** Public read-only state. The canonical way a screen observes this ViewModel. */
    val stateFlow: StateFlow<S> = _state.asStateFlow()

    /**
     * Read-only view of the state, under the shorter name.
     *
     * The same read-only flow as [stateFlow] — not a [MutableStateFlow]. A public
     * mutable handle lets any caller write state behind the ViewModel's back, and the
     * event bus then stops matching what the screen shows. Writes belong to the
     * ViewModel: [updateState] and [setState].
     */
    val state: StateFlow<S> get() = stateFlow

    /** Synchronous snapshot. Use inside reducers and intent handlers. */
    protected val currentState: S get() = _state.value

    /**
     * Updates state by applying [transform] to the current value.
     *
     * Non-suspending, backed by a CAS loop — safe when more than one coroutine
     * writes concurrently. For a value you have already computed, use [setState].
     */
    protected fun updateState(transform: (S) -> S) {
        _state.update(transform)
    }

    /**
     * Directly replaces the current state with [newState].
     * @see updateState
     */
    protected fun setState(newState: S) {
        _state.value = newState
    }

    private val _events = EventBus<E>(diagnostics = diagnostics)

    /** Flow of one-shot events. Collect in your screen's effect layer. */
    val events: Flow<E> get() = _events.flow

    /** Emits a one-shot event. Suspends until the channel accepts it. */
    protected suspend fun emit(event: E) = _events.emit(event)

    /**
     * Tries to emit a one-shot event without suspending.
     * Returns `true` if the event was sent, `false` if the buffer is full.
     */
    protected fun tryEmit(event: E): Boolean = _events.tryEmit(event)

    /**
     * Runs [block] on [vmScope]; on `Result.failure` hands the failure's message to [onError].
     *
     * A thrown exception is converted the same way — `require(...)`, `getOrThrow()`
     * and throwing repository calls land in [onError] instead of propagating out of
     * the coroutine. Before this guard, a throw escaped the scope's `launch`: on Android
     * that is an uncaught exception and kills the process; in tests it cancels a
     * scope shared with the test host and silently freezes every collector on it
     * (discovered when renaming a missing tag threw `AppError.NotFound`).
     *
     * Use when the failure lands in state (`catchTo(label, { msg -> updateState { … } })`).
     * For the one-shot-event case prefer [emitError].
     *
     * Every failure that passes through here is also classified and reported through
     * [diagnostics] before [onError] runs, so ordering is fixed: a breadcrumb the error path
     * emits cannot be recorded ahead of the event it relates to.
     *
     * [CancellationException] never arrives, in either shape it can appear: thrown out of the
     * block, or returned inside the [Result] (which is what `runCatching` does — it captures a
     * cancellation instead of letting it propagate). Both are re-thrown, so a cancelled
     * coroutine is never classified, never reported and never shown to the user.
     *
     * ## [onBeforeReport] — the one exception to that ordering
     *
     * [onBeforeReport] runs *before* the report, and it exists for the fail-open case: a
     * component that proceeds on failure rather than stopping needs its record to travel
     * **with** the report, because the backend attaches the breadcrumb buffer to a report as
     * it stands at the moment the report is made. A record written afterwards rides along with
     * whatever comes next — and when the component's whole point is that nothing else goes
     * wrong, that is nothing at all.
     *
     * This is not hypothetical. The version gate's fail-open record was written from
     * [onError], so it was attached to the following event rather than to the config outage it
     * existed to explain — the precise blindness the record was added to remove. No test caught
     * it, because every test that existed asserted on state.
     *
     * Leave it at the default unless the record is meant to explain *this* failure. A
     * breadcrumb about something else belongs after the report, which is what [onError] is for.
     *
     * @param onBeforeReport Side effect run on the failure, before it is reported. It sits on
     *   the reporting path: it must not block, and it must not throw.
     */
    protected fun catchTo(
        errorLabel: String,
        onError: suspend (String) -> Unit,
        onBeforeReport: suspend (Throwable) -> Unit = {},
        block: suspend () -> Result<*>,
    ): Job =
        vmScope.launch {
            // Both shapes of failure land in the same arm: a `Result.failure` the block
            // returned, and an exception it threw. Cancellation is re-thrown before either,
            // because a cancelled coroutine is structured concurrency working, not a defect —
            // and `Error` (OOM, StackOverflow, AssertionError) propagates to the scope's
            // handler, which is where a fatal error belongs.
            val failure =
                try {
                    block().exceptionOrNull()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    // Any failure a caller's block can raise is a handled failure here; that is
                    // what this primitive is for. `Error` is deliberately not caught.
                    error
                }

            // Cancellation reaches this loop in two shapes, and only one of them is a thrown
            // exception. A `Result.failure(CancellationException(...))` arrives through
            // `exceptionOrNull()` looking like any other failure — so without this check the
            // cancelled coroutine would be classified, reported as a defect and turned into a
            // user-facing message, which is exactly what the contract forbids. `runCatching`
            // is the wrapper that makes this shape common: it captures cancellation instead of
            // letting it propagate, and a block that calls it returns the failure normally.
            if (failure is CancellationException) throw failure

            if (failure != null) {
                // Order matters and is part of the contract: onBeforeReport, then classify,
                // then report, then onError — a breadcrumb the error path emits cannot be
                // recorded ahead of the event it relates to, and a fail-open record travels
                // *with* the report rather than after it.
                //
                // What the reporting channel is allowed to cost is not the same for each step:
                //
                // - `onBeforeReport` and `onError` are the caller's own code. If they throw, that
                //   is a defect in the caller and it escapes to the scope's handler, where a
                //   defect belongs.
                // - `classify` builds the message the user is about to see, so it is the one step
                //   that cannot be skipped. It is called inside the guard below: if it fails,
                //   the failure's own message is the fallback.
                // - `report` is best-effort telemetry. A crash reporter that throws must not
                //   turn a handled failure into a lost one — before this guard, a reporter
                //   exception propagated out of `catchTo` and the user saw nothing at all,
                //   which is the precise opposite of what the funnel is for.
                onBeforeReport(failure)
                val classified = classifyOrFallBack(failure, errorLabel)
                reportOrIgnore(classified)
                onError(classified.message)
            }
        }

    /**
     * [MviDiagnostics.classify], with a fallback for an implementation that throws.
     *
     * The message this returns is what the user sees, so the one step that must not be skipped
     * cannot be. The fallback is the generic classification — the throwable itself, grouped by
     * the call-site label, saying the throwable's own message — which is exactly what
     * [NoOpMviDiagnostics] does.
     */
    private fun classifyOrFallBack(
        error: Throwable,
        errorLabel: String,
    ): MviFailure =
        try {
            diagnostics.classify(error, errorLabel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            // A diagnostics implementation that throws is a defect in the consumer, but the
            // failure it was asked about still has to reach the user.
            NoOpMviDiagnostics.classify(error, errorLabel)
        }

    /**
     * [MviDiagnostics.report], best-effort.
     *
     * Telemetry that fails is not the failure being reported. Swallowing the reporter's own
     * exception is the point: it has no other destination (the port's contract already forbids
     * throwing, and a second report about a failed report would need somewhere to go), and
     * letting it escape would take the user-facing message with it.
     */
    private fun reportOrIgnore(failure: MviFailure) {
        try {
            diagnostics.report(failure.reportable, failure.issueKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            // See the KDoc: best-effort, and the user-facing message is not this call's to lose.
        }
    }

    /**
     * [catchTo] for the common case: a failure becomes a one-shot [E] built from the message.
     *
     * `block` is passed by name because [catchTo] has a defaulted [catchTo.onBeforeReport]
     * before it, and a positional third argument would land there. The one-line version of
     * this function was the only call site that noticed.
     */
    protected fun emitError(
        errorLabel: String,
        errorEvent: (String) -> E,
        block: suspend () -> Result<*>,
    ): Job = catchTo(errorLabel, onError = { msg -> emit(errorEvent(msg)) }, block = block)

    /**
     * Called by the screen layer to dispatch an intent into the MVI loop.
     */
    abstract fun onIntent(intent: I)

    override fun onCleared() {
        _events.close()
        super.onCleared()
    }
}
