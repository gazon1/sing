package com.singularity.todo.core.coroutines

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val TAG = "BackgroundScope"

/**
 * A [CoroutineExceptionHandler] that sends unhandled background failures to [onFailure].
 *
 * ## Why this is a function and not an installed global
 *
 * This was an `object` with a swappable target, installed once at process start. That worked
 * and it was wrong, and the repo has an ADR naming it as interim: a component could start
 * background work and inherit a failure policy chosen by someone else, in a different package,
 * at a different time. `NoStaticProfileAwareCurrentUserRule` exists to ban that shape; this
 * was the one admitted exception, and an argument in a KDoc is not what stops the next change
 * from widening it.
 *
 * The test-safety cost was worse than the coupling. Because the target was process-wide
 * mutable state, `BackgroundFailureHandlerTest` was only correct because `forkEvery = 1` gave
 * every test class its own JVM, and because the class itself had to pin
 * `@Execution(SAME_THREAD)`. Two preconditions for one test's correctness, neither of them
 * visible at the call site. `ForkEveryIsolationTest` exists because the invariant was
 * undocumented; it also stops existing, because there is no longer a global to race on.
 *
 * The replacement is a value. A component that starts background work composes the policy it
 * wants — usually from the [com.singularity.todo.core.observability.CrashReportingPort] it
 * already holds — and holds it. Two components may disagree; neither can be surprised.
 *
 * ## Cancellation
 *
 * [CancellationException] is dropped without reporting. kotlinx.coroutines normally completes
 * such a coroutine as cancelled rather than routing it here at all, so the guard is defensive
 * rather than a fix for an observed case: a cancelled coroutine is structured concurrency
 * doing its job, and a crash report for it would be a defect in the reporter.
 */
fun backgroundFailureHandler(onFailure: (Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, exception ->
        if (exception is CancellationException) return@CoroutineExceptionHandler
        onFailure(exception)
    }

/**
 * The handler used when no reporting port is available — notably JVM desktop, where the
 * crash-reporting port is a deliberate no-op and the Kermit file log is the only sink there
 * is.
 *
 * This is a plain logged failure, not a silent one. A no-op default would reintroduce exactly
 * the failure mode the handler exists to remove, one layer down, and "nothing was logged" and
 * "nothing happened" look identical from the outside.
 */
fun loggingBackgroundFailureHandler(): CoroutineExceptionHandler =
    backgroundFailureHandler { exception ->
        Logger.e(TAG, exception) { "Unhandled background coroutine failure (no reporting port)" }
    }

/**
 * Creates a new background [CoroutineScope] for non-UI work in long-lived components
 * (Koin `single`-bound repositories, wrappers), with [failureHandler] applied.
 *
 * Each call returns a **fresh, independent** scope. The scope is never explicitly cancelled —
 * its lifetime is tied to its owning instance (which is, in production, a Koin `single` that
 * lives for the application process lifetime).
 *
 * Why a factory and not a shared singleton:
 * - Each consumer gets error isolation via its own [SupervisorJob] —
 *   failure in one consumer does not propagate to siblings.
 * - No DI registration required — consumers pass the result directly
 *   in their constructor via `single { MyClass(get(), createBackgroundScope(handler)) }`.
 *
 * Why not `applicationScope()` as the function name:
 * - In Android, "application scope" suggests Application-lifecycle-bound
 *   work (like `ProcessLifecycleOwner`). This function is for **background**
 *   work, not lifecycle-bound work.
 * - `createBackgroundScope()` makes it obvious that each invocation
 *   creates a new scope — discouraging accidental misuse in factory
 *   bindings (which would leak scopes per request).
 *
 * Why `Dispatchers.Default`:
 * - Available on every KMP target (Android, JVM, iOS, Native, JS).
 * - `Main.immediate` requires a UI dispatcher, not present on all targets.
 * - The scope hosts background work like `stateIn` collectors; UI
 *   immediacy is irrelevant here. VM scopes use `Main.immediate`.
 *
 * ## Dispatcher invariant
 *
 * **The dispatcher is injected via DI.** Platform-agnostic code should never hardcode
 * `withContext(Dispatchers.IO)` directly — instead it receives the scope as a
 * constructor parameter from Koin. This makes the dispatcher mockable in tests and
 * explicit in production.
 *
 * The following 6 platform-ported implementations **intentionally** hardcode the
 * dispatcher because they use a blocking native API (I/O, file system, notification
 * posting) and the blocking is the entire point of the operation:
 * [AndroidSecureStorage] (security/IO), [JvmSecureStorage] (security/IO),
 * [AndroidCalendarProvider] (ContentResolver/IO), [JvmNotificationPort] (notify-send/IO),
 * [FileRevealer.jvm] (XDG-open/IO), [AndroidCalendarAppQueries] (JDBC/IO).
 * These are the exceptions, not the rule.
 *
 * ## The failure handler is a required argument
 *
 * There is deliberately no default. A `launch` in a scope with no
 * [CoroutineExceptionHandler] escalates to the platform's default uncaught-exception
 * handler, which kills an Android process outright — so a scope with no policy is a defect
 * waiting to fire at 3am on a device no one is watching. Making the argument required turns
 * "did you think about this?" into a compile error, which is the only check that holds.
 *
 * See `singularity-todo-coroutine-scopes` for full rationale and patterns.
 */
fun createBackgroundScope(failureHandler: CoroutineExceptionHandler): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + failureHandler)
