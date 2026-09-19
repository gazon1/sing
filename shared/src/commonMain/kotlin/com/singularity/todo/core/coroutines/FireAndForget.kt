package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches a fire-and-forget coroutine on [scope] that routes [Result.failure] to [onError]
 * and lets [Result.success] pass silently.
 *
 * **Does NOT broad-catch unchecked exceptions.** If [block] throws an uncaught exception,
 * it propagates to the scope's coroutine exception handler — the caller is responsible
 * for using `Result`-returning APIs.
 *
 * @param errorLabel Human-readable name for the operation (e.g. `"Delete failed"`). Used as
 *                   context in error messages.
 * @param onError   Lambda called with the throwable on [Result.failure]. Routing is the
 *                   caller's responsibility: emit to a channel, update a state field, or log.
 * @param block     Suspend block that returns [Result]. Must be a plain `suspend () -> Result<*>` —
 *                   wrapping a `Result`-returning repo call directly is the canonical pattern.
 * @return [Job] of the launched coroutine. Callers that need cancellation can call [Job.cancel].
 */
inline fun CoroutineScope.fireAndForget(
    errorLabel: String = "Operation failed",
    noinline onError: (Throwable) -> Unit,
    crossinline block: suspend () -> Result<*>,
): Job = launch {
    try {
        block().onFailure(onError)
    } catch (e: Throwable) {
        // Do NOT broad-catch. Uncaught exceptions propagate to the scope's handler,
        // which is the correct behaviour for programming errors / unexpected state.
        throw e
    }
}
