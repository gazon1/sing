package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The outcome of a [boundedRead].
 *
 * A timeout is a *result*, not an exception and not a null value: the read that missed
 * the deadline may yet succeed, so the caller has to be able to say "not in time"
 * without the read's own value being able to say it for it.
 */
sealed interface BoundedReadResult<out T> {

    /**
     * The read answered before the deadline.
     *
     * [value] is null when the read answered null. A read that answers nothing is not a
     * read that missed the deadline, and the two must not be the same value — which is
     * why the answer is carried inside a wrapper instead of being returned bare.
     */
    data class Completed<T>(val value: T) : BoundedReadResult<T>

    /**
     * The deadline passed before the read answered.
     *
     * The read was not stopped: it is still running in the scope it was given, and it may
     * still produce a value. Only the waiting ended.
     */
    data object TimedOut : BoundedReadResult<Nothing>
}

/**
 * Runs [read] in this scope and waits at most [timeoutMs] for its answer.
 *
 * ## Why the read runs somewhere else
 *
 * A deadline can only end a *cooperative* read — one that honours cancellation. A read
 * that blocks inside a driver call (a Room database created on first launch, a locked
 * multi-instance database) ignores cancellation, so a deadline wrapped around it in the
 * caller's own coroutine does not end the wait: the caller waits for the read anyway,
 * and the deadline is decoration.
 *
 * So the read runs in a scope of its own — *this* scope, which is not the caller's — and
 * the caller waits on a promise of the result. A promise can be given up on whether or
 * not the work behind it ever finishes, and that is the whole bound.
 *
 * **The receiver must be a scope of its own.** Passing the caller's own scope brings
 * back the defect this exists to remove: the read would then run in the coroutine the
 * deadline is meant to release.
 *
 * ## What the caller gets to decide
 *
 * The result says *that* the deadline was missed, never *what to do about it*. Serving
 * the last known good value, failing with a machine-shaped code, retrying — those are
 * policies, they differ per caller, and a primitive that picks one of them makes the
 * other callers impossible.
 *
 * ## Failures are not timeouts
 *
 * A read that throws propagates the failure to the caller: it is a failed read, not a
 * slow one, and the bound is not a licence to swallow it. Only the *waiting* is bounded.
 *
 * ## A read left behind
 *
 * A read that misses the deadline keeps running in the scope it was given — it may still
 * succeed, and its result may still be wanted. If it fails after the caller walked away,
 * nobody is left to handle the failure, so [onAbandonedFailure] is told about it rather
 * than it being dropped on the floor.
 *
 * @param timeoutMs The bound on the wait, in milliseconds. The caller's own number:
 *   there is no default, because a bound nobody chose is a bound nobody can explain.
 * @param onAbandonedFailure Told about a failure of a read that was still running when
 *   the caller gave up on it. A cancellation is not a failure and is not reported.
 * @param read The read to run.
 */
suspend fun <T> CoroutineScope.boundedRead(
    timeoutMs: Long,
    onAbandonedFailure: (Throwable) -> Unit = {},
    read: suspend () -> T,
): BoundedReadResult<T> {
    // The read is wrapped before it is started: `await()` on a `Deferred<T?>` cannot
    // tell a read that answered null from a wait that ran out of time, and a primitive
    // that conflates the two cannot be used for a read whose value may legitimately be
    // null.
    val pending = async { BoundedReadResult.Completed(read()) }
    val completed = withTimeoutOrNull(timeoutMs) { pending.await() }
    if (completed == null) {
        // The caller stopped waiting; the read did not. If it fails now, nobody is
        // left to handle the failure, so it is reported rather than dropped.
        pending.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                onAbandonedFailure(cause)
            }
        }
        return BoundedReadResult.TimedOut
    }
    return completed
}
