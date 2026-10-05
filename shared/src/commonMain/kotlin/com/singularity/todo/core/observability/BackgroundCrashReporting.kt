package com.singularity.todo.core.observability

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.backgroundFailureHandler
import com.singularity.todo.core.coroutines.createBackgroundScope
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The issue key every unhandled background coroutine failure is grouped under.
 *
 * Machine-shaped per the [CrashReportingPort] contract: a fixed literal, never derived from
 * the exception's message or type, because it is transmitted off-device. One key for the
 * whole class is intentional — the stack trace in the report is what distinguishes one
 * background failure from another, and splitting the key per call site would need a
 * per-call-site handler at every scope.
 */
const val BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY: String = "background.coroutine_failed"

/**
 * A [CoroutineExceptionHandler] that reports unhandled background failures to [port].
 *
 * ## Why this is composed, not installed
 *
 * There used to be an `installBackgroundCrashReporting(port)` called once at process start
 * that set a process-wide target which every background scope read. The scope factory could
 * not take a dependency: it is a top-level function with no receiver and no Koin handle, and
 * it runs *inside* graph construction. So the compromise was a swappable global, and the
 * consequences were real — a component could not say where its own failures went, the
 * test for that global was only correct because of `forkEvery = 1` plus a
 * `@Execution(SAME_THREAD)` pin, and `NoStaticProfileAwareCurrentUserRule` had to admit this
 * file as the project's one sanctioned global.
 *
 * Now that the scope factory takes its handler as a required argument, a component that
 * already holds a [CrashReportingPort] can build its own policy in one line, and the global
 * is not needed. `MviViewModel` does exactly that for every ViewModel scope.
 *
 * ## The grouping key does not move
 *
 * [BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY] survives the migration unchanged. The key is a
 * contract with the dashboard — a group that already exists under that name must keep
 * receiving failures, or the group silently goes quiet and looks like a fix.
 */
fun crashReportingFailureHandler(port: CrashReportingPort): CoroutineExceptionHandler =
    backgroundFailureHandler { error -> port.report(error, BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY) }

/**
 * A fresh [AutoCloseableCoroutineScope] whose unhandled failures are reported to [port].
 *
 * This is the one-liner that replaced the global. A component that holds a
 * [CrashReportingPort] — which, after `NoUnreportedFailurePath`, is every component that can
 * fail — builds the policy it wants from the dependency it already has, and the scope's
 * failure destination is visible in that component's own signature rather than installed
 * somewhere else in the process.
 *
 * Used as the default for `scope` across the ViewModels, so the common case costs nothing to
 * get right and the uncommon case (a test scope, a shared scope) is a deliberate override.
 */
fun reportingScope(port: CrashReportingPort): AutoCloseableCoroutineScope =
    AutoCloseableCoroutineScope(
        createBackgroundScope(crashReportingFailureHandler(port)).coroutineContext,
    )
