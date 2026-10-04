package com.singularity.todo.test.fakes

import com.singularity.todo.core.observability.CrashReportingPort

/**
 * A [CrashReportingPort] that remembers everything it was handed, so a test can assert on what
 * reached the reporting layer rather than on whether a method was called.
 *
 * ## Why the ordered log, and not just two lists
 *
 * [reports] and [breadcrumbs] answer "what was recorded". [events] answers "in what order", and
 * the order is load-bearing rather than incidental.
 *
 * The reporting backend attaches the breadcrumb buffer to a report **as it is at the moment the
 * report is made** — a breadcrumb logged afterwards is attached to whatever comes *next*, if
 * anything. So a component that fails open has to leave its record *before* the report, or the
 * record does not travel with the report it explains. Two separate lists cannot tell you that:
 * both a correctly ordered pair and a reversed one produce one report and one breadcrumb, and
 * they mean opposite things.
 *
 * This is not hypothetical. `AppVersionGateViewModel` shipped a fail-open breadcrumb that was
 * emitted from the funnel's error handler, which runs *after* the report — so the bypass it
 * recorded was attached to the following event rather than to the outage it belonged to. The
 * property was invisible to every test that existed, because all of them asserted on state.
 *
 * ## Why this is a fake and not a mock
 *
 * No mocking framework: the funnel's contract is "call these two methods", and a double that
 * implements the interface and keeps the calls is the whole requirement. Asserting that
 * `recordBypass()` was *called* would pass against [com.singularity.todo.core.observability.NoOpCrashReportingPort]
 * and prove nothing — the mistake this repository has made twice.
 */
class RecordingCrashReportingPort : CrashReportingPort {

    /** Every `(throwable, issueKey)` pair handed to [report], in call order. */
    val reports: MutableList<Pair<Throwable, String>> = mutableListOf()

    /** Every message handed to [addBreadcrumb], in call order. */
    val breadcrumbs: MutableList<String> = mutableListOf()

    /**
     * A single ordered log of both kinds of call, interleaved as they actually happened.
     *
     * Use this for any assertion about relative order. Use [reports] and [breadcrumbs] for
     * assertions about content.
     */
    val events: MutableList<Event> = mutableListOf()

    /** One recorded call, in the order it was made. */
    sealed interface Event {
        /** [issueKey] and the exact [error] instance the port was handed, unmodified. */
        data class Reported(val error: Throwable, val issueKey: String) : Event

        data class Breadcrumb(val message: String) : Event
    }

    override fun report(error: Throwable, issueKey: String) {
        reports += error to issueKey
        events += Event.Reported(error, issueKey)
    }

    override fun addBreadcrumb(message: String) {
        breadcrumbs += message
        events += Event.Breadcrumb(message)
    }

    /**
     * The index in [events] of the first recorded breadcrumb whose [message] contains [fragment],
     * or -1 when there is none.
     *
     * Substring rather than equality on purpose: a breadcrumb's exact wording is presentation and
     * will be reworded, while the fact that it mentions the bypass is the invariant. An assertion
     * that pins the whole string is an assertion that fails on a copy edit and passes when the
     * breadcrumb stops describing the thing it exists to record.
     */
    fun breadcrumbIndexContaining(fragment: String): Int =
        events.indexOfFirst { it is Event.Breadcrumb && it.message.contains(fragment) }

    /** The index in [events] of the first [Event.Reported] carrying [issueKey], or -1. */
    fun reportIndexWithKey(issueKey: String): Int =
        events.indexOfFirst { it is Event.Reported && it.issueKey == issueKey }
}
