package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * Wraps a [CoroutineScope] (typically a [kotlinx.coroutines.test.TestScope])
 * in an [AutoCloseableCoroutineScope] so it can be passed to a ViewModel
 * constructor that expects `ViewModel(scope)`.
 *
 * The returned scope uses a **child [Job]** so that cancelling the VM's scope
 * (via [AutoCloseableCoroutineScope.close]) does NOT cancel the parent scope.
 * This is essential in tests: cancelling a VM's infinite collectors must not
 * cancel the test body itself.
 *
 * Usage in tests:
 * ```kotlin
 * private fun createVm(scope: TestScope): MyViewModel {
 *     return MyViewModel(deps, testScope(scope))
 * }
 *
 * @Test
 * fun myTest() = runTest {
 *     val vm = createVm(this)
 *     // ...
 *     // VM collectors are children of a child Job — cancelling VM scope
 *     // in afterTest does NOT cancel the test body.
 * }
 * ```
 *
 * ## Ownership contract
 *
 * Every scope in this codebase has exactly one owner, and the owner is whoever created the
 * scope the VM's scope hangs off:
 *
 * - **Production, ViewModel lifetime** — the owner is the ViewModel instance; the framework
 *   calls `close()` on the `addCloseable`-registered scope during `clear()`.
 * - **Production, long-lived component** — the owner is the instance that created the
 *   per-instance scope through `createBackgroundScope()`.
 * - **Tests** — the owner is the scope the test received from `runTest` (or its
 *   `backgroundScope`).
 *
 * The wrapped scope is always hung off a **child** of the owner's job, so `close()` cancels
 * only the VM's subtree and leaves the owner running. The owner ends the VM's work by ending
 * itself (the test finishing, the process exiting); the VM ends its own work by cancelling its
 * child on `close()`.
 */
fun testScope(scope: CoroutineScope): AutoCloseableCoroutineScope {
    // Create a child Job so cancelling the returned scope does NOT cancel
    // the parent scope's root Job.
    val childJob = Job(scope.coroutineContext[Job])
    val ctx = scope.coroutineContext + childJob
    return AutoCloseableCoroutineScope(ctx)
}

/**
 * Shorthand for creating an [AutoCloseableCoroutineScope] from a bare [CoroutineContext].
 */
fun testScope(context: CoroutineContext): AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(context)
