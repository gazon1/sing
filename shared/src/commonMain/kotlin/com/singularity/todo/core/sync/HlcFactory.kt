package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Factory for creating HLC timestamps with a fixed device node ID.
 *
 * [nodeId] is loaded asynchronously via [Deferred] on the IO dispatcher — no [runBlocking].
 * If accessed before loaded, callers block via [Deferred.getCompleted] which is instantaneous
 * after the first await completes.
 */
class HlcFactory(
    private val sessionStore: SessionStore,
    private val clock: Clock
) {
    private val nodeIdDeferred: Deferred<String> = CoroutineScope(Dispatchers.IO + SupervisorJob()).let { scope ->
        kotlinx.coroutines.CompletableDeferred<String>().also { deferred ->
            scope.launch {
                deferred.complete(sessionStore.getOrInitDeviceId())
            }
        }
    }

    // Initialize with a placeholder node ID — will be updated on first tick() call
    private var _lastHlc: Hlc = Hlc.zero("pending")

    /** Creates a new tick HLC for a local event. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tick(): Hlc {
        val node = nodeIdDeferred.getCompleted()
        val newHlc = Hlc.tick(last = _lastHlc, node = node, nowMillis = currentTimeMillis())
        _lastHlc = newHlc
        return newHlc
    }

    /** Merges a received remote HLC with the local state. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tock(remote: Hlc): Hlc {
        val node = nodeIdDeferred.getCompleted()
        val merged = Hlc.tock(local = _lastHlc, remote = remote, node = node, nowMillis = currentTimeMillis())
        _lastHlc = merged
        return merged
    }

    /** Returns the last generated HLC. */
    fun current(): Hlc = _lastHlc

    private fun currentTimeMillis(): Long = clock.now().toEpochMilliseconds()
}
