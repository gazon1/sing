package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope

/**
 * Creates a new background [CoroutineScope] for non-UI work in long-lived
 * components (Koin `single`-bound repositories, wrappers).
 *
 * Each call returns a **fresh, independent** scope. The scope is never
 * explicitly cancelled — its lifetime is tied to its owning instance
 * (which is, in production, a Koin `single` that lives for the application
 * process lifetime).
 *
 * Why a factory and not a shared singleton:
 * - Each consumer gets error isolation via its own [SupervisorJob] —
 *   failure in one consumer does not propagate to siblings.
 * - No DI registration required — consumers pass the result directly
 *   in their constructor via `single { MyClass(get(), createBackgroundScope()) }`.
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
 * ## Every scope carries [BackgroundFailureHandler]
 *
 * Both actuals add [BackgroundFailureHandler] to the context. This is part of the
 * factory's contract, not an implementation detail: a `launch` in a scope without a
 * handler escalates to the platform's default uncaught-exception handler, which kills
 * an Android process outright. A new platform target must add the handler too — there
 * is a test asserting its presence on the JVM actual.
 *
 * See `singularity-todo-coroutine-scopes` for full rationale and patterns.
 */
expect fun createBackgroundScope(): CoroutineScope
