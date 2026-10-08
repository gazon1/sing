package com.singularity.todo.core.ui

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.crashReportingFailureHandler
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
 * ## Errors
 * [catchTo] is the primitive for "run this, route a failure somewhere"; [emitError] is
 * the common case where a failure becomes a one-shot event. [catchTo]'s [onError] is
 * `suspend` so callers can [emit] straight from it — the non-suspend variants forced
 * every call site to wrap the emit in a nested `launch`.
 *
 * ## Usage
 * ```
 * class TagsViewModel(
 *     private val tagRepo: TagsRepository,
 *     scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
 * ) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
 *     initialState = TagsUiState.Loading,
 *     scope = scope,
 * ) {
 *     // no addCloseable(scope) needed here — the base init does it
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
 * @param extraEventCapacity Extra buffer capacity for the event [Channel]. Defaults to [Channel.BUFFERED].
 * @param scope Coroutine scope for collecting flows and launching background work. Defaults
 *   to a scope whose failure policy is composed from [crashReporter], so an unhandled
 *   failure in a ViewModel collector is reported to the same place as a `catchTo` failure
 *   rather than escalating to the platform's uncaught-exception handler — which on Android
 *   kills the process. Override it to pass a test scope or a shared one; the policy still
 *   has to be chosen, never inherited from a process-wide default.
 * @param crashReporter Sink for the failures that pass through [catchTo]. Defaults to a
 *   no-op, so a ViewModel built in a test is silent and nothing reaches for a global —
 *   but a ViewModel that handles real errors should pass the injected port instead of
 *   inheriting the default. See [NoOpCrashReportingPort].
 */
abstract class MviViewModel<S, I : MviIntent, E : MviEvent>(
    initialState: S,
    extraEventCapacity: Int = Channel.BUFFERED,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(
        createBackgroundScope(crashReportingFailureHandler(crashReporter)).coroutineContext,
    ),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /**
     * Scope for collectors and intent handlers. Deliberately not `open` — the eight
     * subclasses that overrode it only ever assigned the same `scope` back to it.
     */
    protected val vmScope: AutoCloseableCoroutineScope = scope

    protected var state: MutableStateFlow<S> = MutableStateFlow(initialState)
        protected get() = field
        private set

    /** Public read-only state. The single way a screen observes this ViewModel. */
    val stateFlow: StateFlow<S> get() = state.asStateFlow()

    /** Synchronous snapshot. Use inside reducers and intent handlers. */
    protected val currentState: S get() = state.value

    /**
     * Updates state by applying [transform] to the current value.
     *
     * Non-suspending, backed by a CAS loop — safe when more than one coroutine
     * writes concurrently. For a value you have already computed, use [setState].
     */
    protected fun updateState(transform: (S) -> S) {
        state.update(transform)
    }

    /**
     * Directly replaces the current state with [newState].
     * @see updateState
     */
    protected fun setState(newState: S) {
        state.value = newState
    }

    private val _events = EventBus<E>(extraEventCapacity)

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
     * Runs [block] on [vmScope]; on `Result.failure` hands `toMessage(errorLabel)` to [onError].
     *
     * A thrown exception is converted the same way — `require(...)`, `getOrThrow()`
     * and throwing repository calls land in [onError] instead of propagating out of
     * the coroutine. Before this guard, a throw escaped `vmScope.launch`: on Android
     * that is an uncaught exception and kills the process; in tests it cancels a
     * scope shared with the test host and silently freezes every collector on it
     * (discovered when renaming a missing tag threw `AppError.NotFound`).
     *
     * Use when the failure lands in state (`catchTo(label, { msg -> updateState { … } })`).
     * For the one-shot-event case prefer [emitError].
     *
     * Every failure that passes through here is also reported to [crashReporter] before
     * [onError] runs, so ordering is fixed: a breadcrumb the error path emits cannot be
     * recorded ahead of the event it relates to. [CancellationException] never arrives —
     * [runCatchingCancellable] re-throws it before the fold — so a cancelled coroutine is
     * never mistaken for a defect.
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
            runCatchingCancellable { block() }.fold(
                onSuccess = { result ->
                    result.onFailure {
                        onBeforeReport(it)
                        crashReporter.report(error = it, issueKey = issueKeyFor(it, errorLabel))
                        onError(it.toMessage(errorLabel))
                    }
                },
                onFailure = { e ->
                    onBeforeReport(e)
                    crashReporter.report(error = e, issueKey = issueKeyFor(e, errorLabel))
                    onError(e.toMessage(errorLabel))
                },
            )
        }

    /**
     * The grouping key for a reported failure: the error's domain code when it has one,
     * otherwise the call-site [errorLabel].
     *
     * Both inputs are machine-shaped by construction — codes are literals like
     * `error.not_found`, labels are fixed strings at the call site — so the key never
     * carries user content off-device. The label fallback is what keeps plain
     * `IllegalArgumentException`s and driver exceptions groupable at all.
     */
    private fun issueKeyFor(error: Throwable, errorLabel: String): String =
        (error as? AppError)?.code ?: errorLabel

    /**
     * [catchTo] for the common case: a failure becomes a one-shot [E] built from the message.
     *
     * `block` is passed by name because [catchTo] has a defaulted [catchTo.onBeforeReport]
     * before it, and a positional third argument would land there. The one-line version of
     * this function was the only call site that noticed.
     */
    protected fun emitError(errorLabel: String, errorEvent: (String) -> E, block: suspend () -> Result<*>): Job =
        catchTo(errorLabel, onError = { msg -> emit(errorEvent(msg)) }, block = block)

    /**
     * Called by the screen layer to dispatch an intent into the MVI loop.
     */
    abstract fun onIntent(intent: I)

    override fun onCleared() {
        _events.close()
        super.onCleared()
    }
}
