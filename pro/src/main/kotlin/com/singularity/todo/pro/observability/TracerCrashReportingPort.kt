// SPDX-License-Identifier: FSL-1.1-ALv2
//
// This file is part of the source-available `pro` catalogue, NOT the Apache-2.0
// core. It is the only place in the repository that may import a proprietary SDK.
// The full terms are in LICENSE.pro at the repository root.
//
// Boundary enforced by `scripts/check-pro-licence-boundary.py`.

package com.singularity.todo.pro.observability

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import ru.ok.tracer.crash.report.TracerCrashReport

/**
 * Android [CrashReportingPort] backed by AppTracer (`ru.ok.tracer`).
 *
 * This class is the reason the `pro` catalogue exists. `ru.ok.tracer` is a
 * proprietary, service-bound SDK — its POM declares "Tracer's License Agreement",
 * it is © VK, and it is not OSI-approved. It used to live in
 * `shared/src/androidMain/…/observability/AndroidCrashReportingPort.kt`, inside
 * the code that is about to be published under Apache-2.0. Moving it here is what
 * makes the core's licence honest.
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
 * `HasTracerConfiguration` implemented by `ProSingularityApp`, and the
 * `ru.ok.tracer` Gradle plugin injects the tokens.
 */
class TracerCrashReportingPort(private val log: Logger) : CrashReportingPort {

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
