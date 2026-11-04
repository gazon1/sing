@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.coroutines

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest

/**
 * A counter that says "run that collection again".
 *
 * The counter is a [StateFlow] rather than an event, because the request has to
 * outlive the failure that caused it: a trigger that is a one-shot event is spent
 * by the attempt that failed, and the next attempt has nothing to answer to.
 *
 * A trigger is *armed* by construction. Nothing consumes it, so a bump that
 * arrives while no collection is listening is still there when one starts
 * listening — the next subscriber sees the current count and begins an attempt.
 */
class ReloadTrigger {
    private val _ticks = MutableStateFlow(0)

    /**
     * The count of the requests made so far.
     *
     * The count itself is not meaningful to a caller; it is what tells the
     * trigger apart from itself, so that two bumps are two bumps and not one.
     *
     * Internal on purpose: [reloadOn] is the only thing that should ever observe
     * this. A caller that combines the counter into its own chain gets the shape
     * this file exists to prevent — a `catch` of its own, sitting outside the
     * attempt, which ends the collection that handled the failure and leaves the
     * next `reload()` shouting at nobody.
     */
    internal val ticks: StateFlow<Int> = _ticks.asStateFlow()

    /**
     * Asks for a fresh attempt.
     *
     * The bump does not start anything by itself — it tells the collection that
     * is listening to start again. A bump made before the collection starts is
     * not lost: the collection begins with the current count either way.
     */
    fun reload() {
        _ticks.value = _ticks.value + 1
    }
}

/**
 * The source flow, subscribed afresh on every [ReloadTrigger.reload] — with the
 * failure handling *inside* the branch.
 *
 * ## What this is for
 *
 * A list that observes a repository can fail: the database is locked, the file is
 * unreadable, the query throws. When that happens the screen has to show the
 * failure and offer a retry, and the retry has to *re-observe* — a screen that
 * shows the old list again is not a retry, it is a stale picture.
 *
 * ## Why the failure handling belongs here and not in the caller
 *
 * The obvious way to write this is a trigger and a `catch` in the caller:
 *
 * ```kotlin
 * trigger.ticks.flatMapLatest { source() }.catch { onFailure(it) }
 * ```
 *
 * That shape does not retry. The `catch` sits on the trigger's own chain, so
 * handling the exception *ends the collection that handled it*: the trigger's
 * subscriber is gone, the next `reload()` bumps a trigger nobody listens to any
 * more, and the screen sits on its spinner for ever. The user's second attempt
 * does nothing, and nothing in the code says why.
 *
 * Here the failure ends only the attempt that failed. The `catch` is inside the
 * branch, so the branch completes and the trigger stays armed, and the next
 * [ReloadTrigger.reload] starts a fresh subscription.
 *
 * That is why the failure handling is an argument here and not a `catch` the
 * caller writes: a caller that owns the `catch` can put it in the one place
 * where it silently breaks the retry.
 *
 * ## What the caller still decides
 *
 * The failure is handed to [onFailure] and nothing else. What the screen shows,
 * whether the cause is reported, whether the state keeps the query the user
 * typed — those are policies, they differ per screen, and a primitive that
 * picks one of them makes the other screens impossible.
 *
 * @param trigger The counter that says when to start over.
 * @param onFailure Told about a failure of one attempt. The attempt ends; the
 *   trigger stays armed.
 * @param source The flow to subscribe to. It is called once per attempt, so a
 *   source that reads a repository reads it again on every retry.
 */
fun <T> reloadOn(
    trigger: ReloadTrigger,
    onFailure: (Throwable) -> Unit,
    source: () -> Flow<T>,
): Flow<T> =
    trigger.ticks.flatMapLatest {
        source().catch { cause -> onFailure(cause) }
    }
