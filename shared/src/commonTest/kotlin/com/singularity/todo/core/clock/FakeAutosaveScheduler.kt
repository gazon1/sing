package com.singularity.todo.core.clock

import kotlinx.coroutines.CompletableDeferred

/**
 * Controllable fake for [AutosaveScheduler]. Tests call [trigger] to fire the tick.
 *
 * ```kotlin
 * val scheduler = FakeAutosaveScheduler()
 * val vm = createVm(autosaveScheduler = scheduler)
 * vm.editBody(id, "<p>content</p>")
 * scheduler.trigger() // synchronously fires the pending awaitTick()
 * advanceUntilIdle()
 * ```
 */
class FakeAutosaveScheduler : AutosaveScheduler {
    private var deferred = CompletableDeferred<Unit>()
    private var _delayMs: Long = 0L // instant by default for tests

    override suspend fun awaitTick() = deferred.await()

    override fun delayMs(): Long = _delayMs

    fun setDelayMs(ms: Long) {
        _delayMs = ms
    }

    /**
     * Fires the pending tick immediately if one is waiting.
     * If no tick is pending, the next [awaitTick] call completes instantly.
     * Resets the deferred for the next tick.
     */
    fun trigger() {
        if (!deferred.isCompleted) {
            deferred.complete(Unit)
        }
        deferred = CompletableDeferred()
    }
}
