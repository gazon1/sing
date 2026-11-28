package com.singularity.todo.core.ui

/**
 * What the MVI runtime needs to know about a failure that the library cannot know by itself.
 *
 * ## Why this seam exists
 *
 * `MviViewModel.catchTo` derives three things from every failure it handles: what to send to
 * the crash reporter, which issue key groups it, and what message to put in front of the user.
 * In the application all three come from `AppError` — its cause chain, its `code`, and its
 * `message` — but the library cannot name `AppError` without importing the application's error
 * vocabulary, which is exactly what this module exists to avoid.
 *
 * So the library owns the *shape* and the consumer owns the *knowledge*:
 *
 * ```
 * // :shared
 * class AppMviDiagnostics(private val port: CrashReportingPort) : MviDiagnostics {
 *     override fun classify(error: Throwable, errorLabel: String): MviFailure =
 *         MviFailure(
 *             reportable = (error as? AppError)?.original() ?: error,
 *             issueKey = (error as? AppError)?.takeIf { it.code != "error.unknown" }?.code ?: errorLabel,
 *             message = error.toMessage(errorLabel),
 *         )
 *     override fun report(error: Throwable, issueKey: String) = port.report(error, issueKey)
 *     override fun log(level: MviLogLevel, message: String) = …
 * }
 * ```
 *
 * One object rather than three parameters, because every `MviViewModel` subclass passes it
 * once and all three members answer the same question: what does this application do when
 * something fails.
 *
 * ## Contract, and what happens when an implementation breaks it
 *
 * Implementations must not throw and must not block: these calls run on the failure arm of a
 * coroutine, where an exception has nowhere useful to go.
 *
 * The runtime does not take that on trust, because the three members have three different
 * costs if they fail:
 *
 * - **[classify] is guarded.** It builds the message the user is about to see, so it is the one
 *   step that cannot be skipped. If it throws, the runtime falls back to the generic
 *   classification — the throwable itself, grouped by the call-site label — which is what
 *   [NoOpMviDiagnostics] does.
 * - **[report] is best-effort.** A crash reporter that throws must not turn a handled failure
 *   into a lost one. Its exception is dropped: the message still reaches the screen, which is
 *   the opposite of what happened before this guard existed.
 * - **[log] is best-effort and already defaulted.** The runtime calls it from the event bus,
 *   where a lost line costs a log line and nothing else.
 *
 * So a broken implementation degrades the diagnosis, never the error handling. A consumer that
 * wants to know its adapter is broken should assert it in a test — the runtime will not raise it,
 * because raising it would defeat the guard.
 */
interface MviDiagnostics {
    /**
     * Names a failure: what to report, how to group it, and what to say about it.
     *
     * @param error the failure as it was raised, not yet unwrapped.
     * @param errorLabel the call-site label, used when the error names nothing itself.
     */
    fun classify(
        error: Throwable,
        errorLabel: String,
    ): MviFailure

    /**
     * Sends a classified failure where the application collects them.
     *
     * @param error the throwable from [MviFailure.reportable] — the one that carries the
     *   stack trace.
     * @param issueKey the grouping identity, from [MviFailure.issueKey]. It is transmitted
     *   off-device, so it is machine-shaped by construction: a domain code or a fixed label,
     *   never user content.
     */
    fun report(
        error: Throwable,
        issueKey: String,
    )

    /**
     * Records a diagnosis line. The default implementation is silent: a library has no sink.
     *
     * Called from the event bus for a refused or discarded event, so it is on a path where
     * throwing would cost an event. The runtime does not guard it; the default cannot throw,
     * and an implementation that does is a defect its own tests should catch.
     */
    fun log(
        level: MviLogLevel,
        message: String,
    ) = Unit
}

/** A failure named for reporting: the throwable, its grouping key, and its user-facing text. */
data class MviFailure(
    /** The throwable to report. For a wrapped error this is the original cause, not the wrapper. */
    val reportable: Throwable,
    /** The grouping identity of the defect. Machine-shaped, never user content. */
    val issueKey: String,
    /** The text the screen shows. */
    val message: String,
)

/** The severities the runtime can report through [MviDiagnostics.log]. */
enum class MviLogLevel {
    Debug,
    Warn,
}

/**
 * The [MviDiagnostics] that classifies generically and reports nothing.
 *
 * This is the library default, and it is deliberately not silent in its classification: an
 * unclassified failure still reports the throwable itself, groups it by the call-site label
 * and says the throwable's own message. A consumer that supplies nothing still gets a working
 * error path; it simply collects the failures nowhere.
 */
object NoOpMviDiagnostics : MviDiagnostics {
    override fun classify(
        error: Throwable,
        errorLabel: String,
    ): MviFailure =
        MviFailure(
            reportable = error,
            issueKey = errorLabel,
            // `?:` alone is not enough: `RuntimeException("")` has a message, and it is empty.
            // An empty banner tells the user nothing, and the label is the only machine-shaped
            // text available here.
            message = error.message?.takeIf { it.isNotBlank() } ?: errorLabel,
        )

    override fun report(
        error: Throwable,
        issueKey: String,
    ) = Unit
}
