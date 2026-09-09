package com.singularity.todo.core.clock

import kotlin.time.Duration.Companion.milliseconds

/**
 * Port for the autosave delay mechanism.
 * Allows testing the autosave logic without real delays.
 */
interface AutosaveScheduler {
    /** Returns when the next autosave tick should fire. */
    suspend fun awaitTick()
}

/**
 * Real implementation using [kotlinx.coroutines.delay].
 */
class DelayAutosaveScheduler(
    private val delayMs: Long = 500L,
) : AutosaveScheduler {
    override suspend fun awaitTick() {
        kotlinx.coroutines.delay(delayMs.milliseconds)
    }
}