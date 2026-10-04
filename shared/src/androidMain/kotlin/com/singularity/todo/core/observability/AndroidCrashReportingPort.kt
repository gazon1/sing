package com.singularity.todo.core.observability

import co.touchlab.kermit.Logger
import ru.ok.tracer.crash.report.TracerCrashReport

/**
 * Android [CrashReportingPort] backed by AppTracer.
 *
 * ## Why every call is wrapped in `runCatching`
 *
 * This class runs on the error path. [report] is called from inside
 * `MviViewModel.catchTo`'s failure arm, so an exception escaping the SDK here
 * would kill the process *while handling an error* — turning a handled failure
 * into the very crash the reporter exists to observe. A failure of the reporter
 * is itself logged through Kermit, the same pattern `FileLogWriter` uses when
 * the disk is full.
 *
 * ## Why the original throwable is forwarded as-is
 *
 * `com.singularity.todo.core.log.RedactingLogWriter` rebuilds a throwable in
 * order to redact it and, in doing so, loses the original stack trace. Grouping
 * and diagnosis both depend on the real frames, so this port receives the
 * untouched original rather than a sanitized copy.
 *
 * No initialization happens here: the SDK configures itself from the
 * `HasTracerConfiguration` implemented by `androidApp`'s `Application`, and the
 * `ru.ok.tracer` Gradle plugin injects the tokens.
 */
internal class AndroidCrashReportingPort(private val log: Logger) : CrashReportingPort {

    override fun report(error: Throwable, issueKey: String) {
        runCatching {
            TracerCrashReport.report(e = error, issueKey = issueKey)
        }.onFailure { failure ->
            log.e(failure) { "AppTracer report failed for issueKey=$issueKey" }
        }
    }

    override fun addBreadcrumb(message: String) {
        runCatching {
            TracerCrashReport.log(message)
        }.onFailure { failure ->
            log.e(failure) { "AppTracer breadcrumb failed" }
        }
    }
}
