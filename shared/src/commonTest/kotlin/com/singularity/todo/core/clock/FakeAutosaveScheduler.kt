package com.singularity.todo.core.clock

import kotlinx.coroutines.CompletableDeferred

/**
 * Controllable fake for [AutosaveScheduler]. Tests call [trigger] to fire the tick.
 */
class FakeAutosaveScheduler : AutosaveScheduler {
    private val deferred = CompletableDeferred<Unit>()

    suspend override fun awaitTick() = deferred.await()

    fun trigger() {
        // If already waiting, fire immediately; otherwise do nothing
    }
}