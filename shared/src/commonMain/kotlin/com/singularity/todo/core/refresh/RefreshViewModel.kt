package com.singularity.todo.core.refresh

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The intent of a refresh.
 */
sealed interface RefreshIntent : MviIntent {
    /** The user pulled the content down. */
    data object Refresh : RefreshIntent
}

/**
 * The ViewModel of the refresh gesture.
 *
 * One per screen, but they all ask the same [RefreshCoordinator] — the screen
 * owns the *report*, not the run. Two screens that both pull at once must
 * not archive the completed work twice, and the coordinator is what makes
 * that true.
 *
 * ## The flag comes down in a `finally`
 *
 * A refresh that failed must not leave the screen stuck showing the
 * indicator. The flag is cleared in a `finally` for exactly that reason: the
 * only way out of "refreshing" is the end of the run, whatever the run
 * produced.
 */
class RefreshViewModel(
    private val coordinator: RefreshCoordinator,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<RefreshUiState, RefreshIntent, RefreshUiEvent>(
        initialState = RefreshUiState.Idle,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    override fun onIntent(intent: RefreshIntent) {
        when (intent) {
            RefreshIntent.Refresh -> refresh()
        }
    }

    /**
     * Starts a refresh — or does nothing when one is already running.
     *
     * The guard is the screen's half of the rule that a refresh does not
     * become two. The coordinator's half is that a request which arrives
     * during a run joins it; this half stops the request from being made at
     * all, so the gesture is inert rather than merely harmless.
     */
    @Suppress("TooGenericExceptionCaught") // a failure outside the run may be anything; it must still be reported
    private fun refresh() {
        if (currentState is RefreshUiState.Refreshing) return
        setState(RefreshUiState.Refreshing)
        vmScope.launch {
            try {
                emit(RefreshUiEvent.Finished(coordinator.request()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The coordinator turns a failing step into an outcome, so this
                // is the path for a failure *outside* the run. It still owes
                // the user a report: a gesture that produced nothing visible
                // reads as a gesture that was ignored.
                crashReporter.report(e, REFRESH_FAILED)
                emit(
                    RefreshUiEvent.Finished(
                        RefreshOutcome(
                            archived = Result.failure(e),
                            sync = SyncOutcome.CouldNotStart(e.toAppError()),
                        ),
                    ),
                )
            } finally {
                setState(RefreshUiState.Idle)
            }
        }
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val REFRESH_FAILED = "refresh.failed"
    }
}
