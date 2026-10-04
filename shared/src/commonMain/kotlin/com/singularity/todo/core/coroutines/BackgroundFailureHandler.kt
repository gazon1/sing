package com.singularity.todo.core.coroutines

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

private const val TAG = "BackgroundScope"

/**
 * The [CoroutineExceptionHandler] that every scope from [createBackgroundScope] carries, and
 * the single place an unhandled failure in background work ends up.
 *
 * ## Why this exists
 *
 * `createBackgroundScope()` is `SupervisorJob() + Dispatchers.Default` and nothing else. A
 * `launch` whose body throws is therefore routed by kotlinx.coroutines to the thread's default
 * uncaught-exception handler, which on Android is the platform's `KillApplicationHandler` —
 * **the process dies**, with nothing but a stack trace on the dying thread. That was true of
 * every background launch in the app: the 9 ViewModels that launch work outside the
 * [com.singularity.todo.core.ui.MviViewModel] error funnel account for 26 such calls between
 * them, and the Koin `single`-bound repositories own more. A guard is only worth having where
 * something is funnelled into, and this scope factory was the bottom of that funnel with a hole
 * in it.
 *
 * With this handler in the context, a background failure is **reported and survived**: the
 * coroutine dies, its siblings keep running, and the process stays up. That is the right
 * trade — a collector that crashed is a defect to triage, not a reason to lose the user's
 * unsaved state.
 *
 * ## Why the target is replaceable rather than injected
 *
 * [install] is called once at process start, from `SingularityApp.onCreate`, with a callback
 * built over the Koin-resolved `CrashReportingPort`. The scope factory cannot take that
 * dependency as a parameter: it is a top-level `expect fun` with no receiver and no Koin
 * handle, and it runs *inside* Koin graph construction. A swappable target keeps `shared`'s
 * package graph acyclic (`core.coroutines` depends on Kermit alone, not on
 * `core.observability`) and keeps the scope's identity stable, so a scope created before
 * [install] still reports to the handler installed afterwards.
 *
 * The deliberate exception to "everything is injected" is that the *reporter* stays injected —
 * it is a dependency and it is resolved from Koin — while *where an unhandled failure goes* is
 * bootstrap state, the same category as the installed uncaught-exception handler. A global
 * variable holding the crash reporter would have been the wrong trade: it is test-host global
 * mutable state, and a `LogEventOncePerDay`-style test that forgot to reset it would leak
 * reports into the next test.
 *
 * ## The default target
 *
 * Before [install] is called, a failure is logged through Kermit at `Error` and dropped. That
 * default is deliberate rather than a no-op: a silent default would reintroduce exactly the
 * failure mode this object removes, one layer down. On JVM/desktop the Kermit file log is the
 * only sink there is, and nothing installs a reporter on that platform — so the default *is*
 * the desktop behaviour, deliberately, and the file log is what an unreported background
 * failure looks like there.
 *
 * ## Cancellation
 *
 * [CancellationException] is dropped without reporting. kotlinx.coroutines normally completes
 * such a coroutine as cancelled rather than routing it here at all, so the guard is defensive
 * rather than a fix for an observed case: a cancelled coroutine is structured concurrency doing
 * its job, and a crash report for it would be a defect in the reporter.
 */
object BackgroundFailureHandler : CoroutineExceptionHandler {

    /**
     * `CoroutineExceptionHandler` is a plain interface (not a `fun interface`), so an
     * implementation has to hand back the key itself. The interface's companion *is* the key.
     */
    override val key: CoroutineContext.Key<*>
        get() = CoroutineExceptionHandler

    @Volatile
    private var onFailure: ((Throwable) -> Unit)? = null

    /**
     * Routes future background failures to [onFailure]; `null` restores the Kermit default.
     *
     * Safe to call more than once — a later call replaces the target, it does not stack. The
     * callback is read on each failure rather than captured, so replacing it affects scopes
     * that were created earlier.
     */
    fun install(onFailure: ((Throwable) -> Unit)?) {
        this.onFailure = onFailure
    }

    /** Whether a target has been installed. Intended for tests and startup diagnostics. */
    internal val isInstalled: Boolean
        get() = onFailure != null

    /**
     * Delegates to the installed target, or logs. **Never rethrows**: this handler runs on a
     * coroutine that is already failing, and rethrowing here would re-escalate a reported
     * background defect into the process death this class exists to prevent.
     */
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is CancellationException) return
        val current = onFailure
        if (current == null) {
            Logger.e(TAG, exception) { "Unhandled background coroutine failure (no reporter installed)" }
            return
        }
        current(exception)
    }
}
