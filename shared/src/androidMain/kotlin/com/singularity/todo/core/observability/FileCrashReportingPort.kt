package com.singularity.todo.core.observability

import co.touchlab.kermit.Logger

/**
 * The free-core [CrashReportingPort] for Android: everything goes to the log file.
 *
 * ## Why this class exists
 *
 * The Android implementation used to be `AndroidCrashReportingPort`, backed by
 * AppTracer (`ru.ok.tracer`). That SDK is proprietary — © VK, licensed under
 * "Tracer's License Agreement", not OSI-approved — so it could not ship inside
 * code published under Apache-2.0. It now lives in the source-available `pro`
 * catalogue as `TracerCrashReportingPort`, and this class is what the free build
 * binds instead (ADR `2026-10-05-provenance-audit` §3).
 *
 * ## What is actually lost, stated plainly
 *
 * The honest answer is: the network. This class writes the same information to
 * disk that the vendor SDK would have uploaded, and a user who exports the log
 * bundle can send it. What a user *cannot* do is have crashes collected on their
 * behalf without asking. That is a real reduction in capability, not a cosmetic
 * one, and it is the deliberate price of an Apache-2.0 core.
 *
 * What is **not** lost is the record. The app already keeps a rolling Kermit file
 * log with a configurable budget, so a crash survives process death and is
 * readable after the fact. For a local-first app whose selling point is that data
 * stays on the device, a log the user already owns is a defensible default rather
 * than a placeholder.
 *
 * ## Why every call is wrapped in `runCatching`
 *
 * This class runs on the error path — [report] is called from inside
 * `MviViewModel.catchTo`'s failure arm, so a failure escaping here would kill the
 * process *while handling an error*, turning a handled failure into the very
 * crash the reporter exists to observe. A failure of the reporter is itself
 * logged, the same pattern `FileLogWriter` uses when the disk is full.
 */
internal class FileCrashReportingPort(private val log: Logger) : CrashReportingPort {

    override fun report(error: Throwable, issueKey: String) {
        runCatching {
            // The original throwable goes to the logger whole. Redaction is
            // RedactingLogWriter's job, and it is applied on the way out — this
            // port does not rebuild the throwable, because doing so would lose the
            // stack trace that makes the entry worth reading.
            log.e(error) { "crash-report issueKey=$issueKey" }
        }.onFailure { failure ->
            // Reported without the cause attached: the cause here is a failure of
            // the logger, and nesting loggers is how a log line becomes a crash.
            log.e { "crash-report itself failed for issueKey=$issueKey: ${failure.message}" }
        }
    }

    override fun addBreadcrumb(message: String) {
        runCatching {
            log.d { "breadcrumb: $message" }
        }.onFailure { failure ->
            log.e { "breadcrumb logging failed: ${failure.message}" }
        }
    }
}
