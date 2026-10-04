package com.singularity.todo.core.observability

import com.singularity.todo.core.coroutines.BackgroundFailureHandler

/**
 * The issue key every unhandled background coroutine failure is grouped under.
 *
 * Machine-shaped per the [CrashReportingPort] contract: a fixed literal, never derived from
 * the exception's message or type, because it is transmitted off-device. One key for the
 * whole class is intentional — the stack trace in the report is what distinguishes one
 * background failure from another, and splitting the key per call site would need a
 * per-call-site handler, which is precisely the global-state plumbing being avoided.
 */
const val BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY: String = "background.coroutine_failed"

/**
 * Routes unhandled failures in every [com.singularity.todo.core.coroutines.createBackgroundScope]
 * scope to [port]. Call once at process start, after Koin is available.
 *
 * `SingularityApp.onCreate` calls this with the Koin-resolved `CrashReportingPort`. Desktop
 * deliberately does not: `JvmCrashReportingPort` is a no-op, so installing it would replace
 * a Kermit log line (the only sink desktop has) with silence. The uninstalled default is a
 * logged failure precisely so that platform keeps its visibility.
 *
 * Idempotent — a later call replaces the previous target rather than stacking a second one.
 */
fun installBackgroundCrashReporting(port: CrashReportingPort) {
    BackgroundFailureHandler.install { error ->
        port.report(error, BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY)
    }
}
