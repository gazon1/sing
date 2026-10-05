package com.singularity.todo.core.observability

/**
 * Reports handled failures to the crash-reporting backend.
 *
 * Android: the rolling Kermit log, via `FileCrashReportingPort`. JVM: a no-op —
 * desktop keeps its Kermit file log as its only sink.
 *
 * The Android binding is an interface-typed `single` rather than a concrete type
 * because the source-available `pro` catalogue substitutes a vendor-backed
 * implementation for it. The free core has no vendor dependency at all.
 *
 * ## Contract
 *
 * Implementations **must not throw** and must not block the caller. [report] is
 * invoked from inside `MviViewModel.catchTo`'s failure arm, so a reporter that
 * threw would escalate a handled error into a process crash. Each platform
 * implementation wraps its SDK calls accordingly.
 *
 * ## What belongs in an [issueKey]
 *
 * An issue key is the grouping identity of an error: everything sharing one key
 * is one defect in the dashboard. It is transmitted off-device, so it must be a
 * stable machine identifier — a domain [com.singularity.todo.core.error.AppError.code]
 * where one is available, otherwise a fixed per-call-site label. Never a message,
 * an exception text, an entity id, or anything else derived from user input.
 */
interface CrashReportingPort {

    /**
     * Reports [error] as a non-fatal, grouped under [issueKey].
     *
     * [error] is passed **unmodified** — the full original throwable, stack trace
     * and all. Grouping and diagnosis both depend on it. Redaction of the
     * message is the caller's concern, not this port's; see
     * `com.singularity.todo.core.log.redact`.
     */
    fun report(error: Throwable, issueKey: String)

    /**
     * Adds [message] to a bounded ring buffer that is attached to subsequent
     * events, so a report shows what preceded it.
     *
     * The buffer is finite (64 KB on AppTracer). Breadcrumbs are for state
     * transitions — sync started, profile switched, app foregrounded — never for
     * task titles, note bodies, or other user content, and never at a rate that
     * would evict the entries worth reading. If in doubt, omit it.
     */
    fun addBreadcrumb(message: String)
}

/**
 * The [CrashReportingPort] that discards everything.
 *
 * Bound on JVM, used as the default parameter of `MviViewModel`'s reporter so a
 * ViewModel constructed in a test is silent without any global to reset.
 */
class NoOpCrashReportingPort : CrashReportingPort {
    override fun report(error: Throwable, issueKey: String) = Unit
    override fun addBreadcrumb(message: String) = Unit
}
