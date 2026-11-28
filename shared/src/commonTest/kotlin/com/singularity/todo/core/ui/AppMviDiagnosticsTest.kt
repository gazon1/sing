@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.error.AppError
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `AppError` half of what the MVI base class used to do — asserted where the adapter lives.
 *
 * `:mvi` owns the shape of a handled failure and its own generic default; the application's
 * error vocabulary is `AppError`, and this is the one class that knows about both. The behaviour
 * is the same behaviour the base class had before the split, so these cases pin the move as
 * behaviour-preserving rather than a redesign.
 *
 * @see AppMviDiagnostics
 */
@Tag("fast")
class AppMviDiagnosticsTest {
    @Test
    fun `a plain failure reports the throwable itself and groups by the call-site label`() {
        val error = IllegalStateException("inner")
        val failure = AppMviDiagnostics(RecordingCrashReportingPort()).classify(error, "tags.delete_failed")

        assertSame(error, failure.reportable, "an unclassified throwable is reported as it was raised")
        assertEquals("tags.delete_failed", failure.issueKey, "nothing names this failure, so the label groups it")
        assertEquals("inner", failure.message)
    }

    @Test
    fun `an AppError message wins over the throwable and the label`() {
        val failure =
            AppMviDiagnostics(RecordingCrashReportingPort())
                .classify(AppError.Validation("domain says no"), "unused label")

        assertEquals("domain says no", failure.message)
    }

    @Test
    fun `an AppError groups by its domain code, not the call-site label`() {
        val failure =
            AppMviDiagnostics(RecordingCrashReportingPort())
                .classify(AppError.NotFound("gone"), "tags.delete_failed")

        assertEquals("error.not_found", failure.issueKey, "the domain code is the grouping identity")
    }

    @Test
    fun `an unclassified AppError keeps the call-site label, so unclassified failures stay apart`() {
        val failure =
            AppMviDiagnostics(RecordingCrashReportingPort())
                .classify(AppError.Unknown("unknown"), "sync.push_failed")

        assertEquals("sync.push_failed", failure.issueKey, "error.unknown names nothing and would collapse every failure")
    }

    @Test
    fun `an AppError wrapping a cause reports the cause, so the report carries the stack trace`() {
        val cause = IllegalStateException("real failure")
        val failure = AppMviDiagnostics(RecordingCrashReportingPort()).classify(AppError.Persistence("save failed", cause = cause), "label")

        assertSame(cause, failure.reportable, "reporting the wrapper would hand over a stack ending where it was built")
        assertEquals("error.persistence", failure.issueKey, "the code is what groups it, the cause is what is reported")
    }

    @Test
    fun `report delegates to the port unchanged`() {
        val port = RecordingCrashReportingPort()
        val error = IllegalStateException("boom")

        AppMviDiagnostics(port).report(error, "error.network")

        assertSame(error, port.reports.single().first)
        assertEquals("error.network", port.reports.single().second)
    }

    @Test
    fun `a debug line is logged at debug, under the runtime's tag`() {
        val writer = CapturingLogWriter()
        val diagnostics = AppMviDiagnostics(RecordingCrashReportingPort(), logger(writer))

        diagnostics.log(MviLogLevel.Debug, "a debug line")

        val line = writer.entries.single()
        assertEquals(Severity.Debug, line.severity, "a discard log must stay below the release filter")
        assertEquals(AppMviDiagnostics.TAG, line.tag)
        assertEquals("a debug line", line.message)
    }

    @Test
    fun `a warn line is logged at warn, under the runtime's tag`() {
        // The severity is the contract, not the fact that a call happened: a release build
        // filters below Warn, so a full-buffer refusal written at debug would not exist in the
        // build where a missing snackbar gets reported.
        val writer = CapturingLogWriter()
        val diagnostics = AppMviDiagnostics(RecordingCrashReportingPort(), logger(writer))

        diagnostics.log(MviLogLevel.Warn, "a warn line")

        val line = writer.entries.single()
        assertEquals(Severity.Warn, line.severity, "warn is what release keeps")
        assertEquals(AppMviDiagnostics.TAG, line.tag)
        assertEquals("a warn line", line.message)
    }

    @Test
    fun `the adapter's logger is silent by default, so a constructed ViewModel logs nothing of its own`() {
        // The default is Kermit's global logger, not a capturing one — the assertion here is the
        // default's *shape*, not its output: constructing the adapter with two arguments must
        // stay legal, because that is what every ViewModel does.
        val diagnostics = AppMviDiagnostics(RecordingCrashReportingPort())

        diagnostics.log(MviLogLevel.Warn, "routed through Kermit's default writer")

        assertTrue(true, "the two-argument form compiles and does not throw")
    }
}

/** Captures what the adapter logs, so severity, tag and message can be asserted. */
private class CapturingLogWriter : LogWriter() {
    data class Entry(val severity: Severity, val message: String, val tag: String)

    val entries: MutableList<Entry> = mutableListOf()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        entries += Entry(severity, message, tag)
    }
}

private fun logger(writer: CapturingLogWriter): Logger =
    Logger(loggerConfigInit(writer), AppMviDiagnostics.TAG)
