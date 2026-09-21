package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Wraps a [CoroutineScope] (typically a [kotlinx.coroutines.test.TestScope])
 * in an [AutoCloseableCoroutineScope] so it can be passed to a ViewModel
 * constructor that expects `ViewModel(closeable)`.
 *
 * Usage in tests:
 * ```kotlin
 * private fun createVm(scope: CoroutineScope): MyViewModel {
 *     return MyViewModel(deps, testScope(scope))
 * }
 *
 * @Test
 * fun myTest() = runTest {
 *     val vm = createVm(this)
 *     // ...
 * }
 * ```
 */
fun testScope(scope: CoroutineScope): AutoCloseableCoroutineScope =
    AutoCloseableCoroutineScope(scope.coroutineContext)

/**
 * Shorthand for creating an [AutoCloseableCoroutineScope] from a bare [CoroutineContext].
 */
fun testScope(context: CoroutineContext): AutoCloseableCoroutineScope =
    AutoCloseableCoroutineScope(context)
