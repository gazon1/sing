package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
fun testScope(context: CoroutineContext): AutoCloseableCoroutineScope =
    AutoCloseableCoroutineScope(context)
