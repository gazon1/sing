package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.platform.Clock

/**
 * Factory for creating HLC timestamps with a fixed device node ID.
 */
class HlcFactory(
    private val sessionStore: SessionStore,
    private val clock: Clock
) {
    private val nodeId: String by lazy { sessionStore.deviceIdBlocking() }

    private var _lastHlc: Hlc = Hlc.zero(nodeId)

    /**
     * Creates a new tick HLC for a local event.
     */
    fun tick(): Hlc {
        val newHlc = Hlc.tick(last = _lastHlc, node = nodeId, nowMillis = currentTimeMillis())
        _lastHlc = newHlc
        return newHlc
    }

    /**
     * Merges a received remote HLC with the local state.
     */
    fun tock(remote: Hlc): Hlc {
        val merged = Hlc.tock(local = _lastHlc, remote = remote, node = nodeId, nowMillis = currentTimeMillis())
        _lastHlc = merged
        return merged
    }

    /**
     * Returns the current HLC (last generated).
     */
    fun current(): Hlc = _lastHlc

    private fun currentTimeMillis(): Long = clock.now().toEpochMilliseconds()
}
