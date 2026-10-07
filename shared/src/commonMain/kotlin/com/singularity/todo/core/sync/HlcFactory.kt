package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Factory for creating HLC timestamps with a fixed device node ID.
 *
 * [nodeId] is loaded asynchronously via [Deferred] on the injected [scope];
 * if accessed before loaded, callers block via [Deferred.getCompleted] which
 * is instantaneous after the first await completes.
 *
 * @param scope Injected [AutoCloseableCoroutineScope]. Callers must bind to a
 *              lifecycle that calls [AutoCloseable.close] when done.
 */
open class HlcFactory(
    private val sessionStore: SessionStore,
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope,
) {
    private val nodeIdDeferred: Deferred<String> = kotlinx.coroutines.CompletableDeferred<String>().also { deferred ->
        scope.launch {
            deferred.complete(sessionStore.getOrInitDeviceId())
        }
    }

    // Initialize with a placeholder node ID — will be updated on first tick() call
    private var _lastHlc: Hlc = Hlc.zero("pending")

    /**
     * Creates a new tick HLC for a local event.
     *
     * ## Why this is synchronized
     *
     * The read-modify-write below is the whole bug it used to have. `Hlc.tick` is pure, so
     * two threads entering together both read the same `_lastHlc`, both see the same
     * physical time, both compute `counter + 1` — and produce **the same HLC string**. One
     * write is lost.
     *
     * That is not a cosmetic collision. The HLC is the ordering key that resolves
     * conflicts, so two edits carrying an identical timestamp have no order between them,
     * and which one wins stops being a decision the code makes and becomes whichever the
     * server saw first. Now that `supabase/migrations/` carries `server_version` and the
     * optimistic-concurrency path is being built on this, that ambiguity would decide real
     * data.
     *
     * The same reasoning covers [current], which reads the field without any protection at
     * all — a caller observing a half-updated state gets a timestamp that no `tick` ever
     * returned.
     *
     * `synchronized` rather than an atomic, and synchronized rather than a coroutine Mutex,
     * because these are plain non-suspend functions callable from any thread — Mutex would
     * mean making every caller suspend. The shape matches
     * [com.singularity.todo.core.billing.NoopSubscriptionProvider], which guards its map
     * for the same reason.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tick(): Hlc = synchronized(this) {
        val node = nodeIdDeferred.getCompleted()
        Hlc.tick(last = _lastHlc, node = node, nowMillis = currentTimeMillis()).also {
            _lastHlc = it
        }
    }

    /**
     * Merges a received remote HLC with the local state.
     *
     * Synchronized for the same reason as [tick]: `tock` is a read-modify-write on
     * `_lastHlc`, and a `tock` racing a `tick` loses a counter increment the same way.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tock(remote: Hlc): Hlc = synchronized(this) {
        val node = nodeIdDeferred.getCompleted()
        Hlc.tock(local = _lastHlc, remote = remote, node = node, nowMillis = currentTimeMillis()).also {
            _lastHlc = it
        }
    }

    /** Returns the last generated HLC. Guarded for the reason given on [tick]. */
    fun current(): Hlc = synchronized(this) { _lastHlc }

    private fun currentTimeMillis(): Long = clock.now().toEpochMilliseconds()
}
