package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.MviDiagnostics
import com.singularity.todo.core.ui.MviFailure
import com.singularity.todo.core.ui.MviLogLevel

/**
 * A [MviDiagnostics] that records what it was asked to do, so a test can assert the *absence*
 * of a report — which is the whole point of a sink that a production bus never reports to.
 *
 * Lives here rather than in the application's fakes because the library must not depend on
 * `:shared`'s test sources: this module has no application to fake.
 */
internal class RecordingMviDiagnostics : MviDiagnostics {
    /** Every report, in order, as `issueKey to error`. */
    val reports: MutableList<Pair<String, Throwable>> = mutableListOf()

    /** Every log line, in order, as `level to message`. */
    val lines: MutableList<Pair<MviLogLevel, String>> = mutableListOf()

    override fun classify(
        error: Throwable,
        errorLabel: String,
    ): MviFailure = MviFailure(reportable = error, issueKey = errorLabel, message = error.message ?: errorLabel)

    override fun report(
        error: Throwable,
        issueKey: String,
    ) {
        reports += issueKey to error
    }

    override fun log(
        level: MviLogLevel,
        message: String,
    ) {
        lines += level to message
    }
}
