package com.singularity.todo.core.error

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and captures its result, without swallowing [CancellationException].
 *
 * Unlike [kotlin.runCatching], this function re-throws [CancellationException] rather
 * than wrapping it in a [Result.failure]. In suspend functions, `CancellationException`
 * is the cancellation mechanism and must propagate to abort the calling coroutine;
 * catching it silently allows a cancelled operation to appear successful to callers.
 *
 * - `CancellationException` → re-thrown (cancellation propagates)
 * - `Exception` / `RuntimeException` / `AppError` → caught, returned as [Result.failure]
 * - `Error` (OOM, StackOverflow, AssertionError) → propagates (never silently swallowed)
 *
 * The [ExperimentalContracts] annotation is required for the [contract] declaration.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
