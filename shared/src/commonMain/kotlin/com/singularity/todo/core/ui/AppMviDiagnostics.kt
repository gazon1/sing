package com.singularity.todo.core.ui

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort

/**
 * The application's answer to the MVI runtime's diagnostics seam.
 *
 * `:mvi` owns the *shape* of a handled failure — what to report, how to group it, what to say
 * — but not the vocabulary that fills it in. That vocabulary is [AppError]: its cause chain,
 * its machine-shaped [AppError.code], and its user-facing message. This adapter is the one
 * place the two meet, so `AppError`, `Either`, `CrashReportingPort` and Kermit never enter the
 * library.
 *
 * Every ViewModel passes `AppMviDiagnostics(crashReporter)` where it used to pass
 * `crashReporter`, and the `NoDivergentScopeAndReporter` detekt rule keeps the pairing honest.
 *
 * @param port the crash reporter the ViewModel was constructed with.
 */
class AppMviDiagnostics(private val port: CrashReportingPort) : MviDiagnostics {
    /**
     * Classifies [error] the way the base class always did, with the `AppError` knowledge
     * moved here.
     *
     * The grouping key is the domain code of the error that names the failure, otherwise the
     * call-site [errorLabel]. [error] is the failure as it was raised, not the throwable that
     * is reported: an [AppError] names what went wrong (`error.not_found`), so an error that
     * carries a cause still groups by its own code — the cause is what gets reported, the code
     * is what groups it.
     *
     * The unclassified code is the one exception: `error.unknown` names nothing, and grouping
     * every unclassified failure under it would collapse them into a single issue. Those keep
     * the call-site label, which is what keeps them apart — the same reason plain
     * `IllegalArgumentException`s and driver exceptions fall back to it.
     *
     * Both inputs are machine-shaped by construction — codes are literals like
     * `error.not_found`, labels are fixed strings at the call site — so the key never
     * carries user content off-device.
     */
    override fun classify(
        error: Throwable,
        errorLabel: String,
    ): MviFailure =
        MviFailure(
            reportable = (error as? AppError)?.original() ?: error,
            issueKey = issueKeyFor(error, errorLabel),
            message = error.toMessage(errorLabel),
        )

    /** @see CrashReportingPort.report */
    override fun report(
        error: Throwable,
        issueKey: String,
    ) = port.report(error, issueKey)

    /**
     * Kermit, under the tag the runtime's own diagnosis used before the split.
     *
     * [MviLogLevel.Warn] maps to `w` and [MviLogLevel.Debug] to `d`, which is the exact pair
     * the release filter distinguishes.
     */
    override fun log(
        level: MviLogLevel,
        message: String,
    ) {
        when (level) {
            MviLogLevel.Debug -> Logger.withTag(TAG).d { message }
            MviLogLevel.Warn -> Logger.withTag(TAG).w { message }
        }
    }

    private fun issueKeyFor(
        error: Throwable,
        errorLabel: String,
    ): String =
        when (error) {
            is AppError -> if (error.code == UNCLASSIFIED_ERROR_CODE) errorLabel else error.code
            else -> errorLabel
        }

    private companion object {
        /** The tag the runtime's log lines have always carried. */
        const val TAG = "EventBus"

        /** The code of an error that does not name what went wrong. */
        const val UNCLASSIFIED_ERROR_CODE = "error.unknown"
    }
}
