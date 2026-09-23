package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Auto-cancelling [CoroutineScope] whose [kotlin.AutoCloseable.close] cancels the underlying
 * coroutine context. Used with [androidx.lifecycle.ViewModel]'s `addCloseable` mechanism
 * (lifecycle 2.8+) so a Tier-1 ViewModel doesn't need to override `onCleared()` just to
 * cancel its scope.
 *
 * Usage in a ViewModel:
 * ```kotlin
 * class MyViewModel(
 *     private val deps: MyDeps,
 *     private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
 * ) : ViewModel() {
 *     init {
 *         addCloseable(scope)
 *     }
 * }
 * ```
 *
 * Why a separate `init { addCloseable(scope) }` instead of `ViewModel(scope)`:
 * `AutoCloseableCoroutineScope` implements both [CoroutineScope] and [kotlin.AutoCloseable],
 * so passing it as a constructor argument hits Kotlin's overload-resolution ambiguity between
 * `ViewModel(viewModelScope: CoroutineScope)` and `ViewModel(vararg closeables: AutoCloseable)`.
 * `addCloseable()` is unambiguous.
 *
 * Tier-1 ViewModels (those whose only cleanup is `scope.cancel()`) use this pattern.
 * Tier-2 ViewModels retain their manual `onCleared()` override.
 */
class AutoCloseableCoroutineScope(
    override val coroutineContext: CoroutineContext,
) : AutoCloseable, CoroutineScope {

    /**
     * The [Job] of this scope. Exposed so tests can cancel only this scope's child jobs
     * without cancelling the root [Job] of the surrounding [CoroutineScope].
     */
    val job: Job? = coroutineContext[Job]

    override fun close() {
        cancel()
    }

    companion object {
        /**
         * Creates an [AutoCloseableCoroutineScope] backed by [createBackgroundScope].
         */
        operator fun invoke(): AutoCloseableCoroutineScope =
            AutoCloseableCoroutineScope(createBackgroundScope().coroutineContext)
    }
}
