package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
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
 * @param deviceIdProvider Source of the stable node id (not the whole auth seam).
 */
open class HlcFactory(
    private val deviceIdProvider: DeviceIdProvider,
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope,
    private val hlcStore: HlcStore? = null,
) {
    /**
     * Always reaches a terminal state — completed or completed-exceptionally.
     *
     * `deferred.complete(provider.getOrInitDeviceId())` looked equivalent and was not:
     * when the provider threw, the launch body died before `complete` was reached and
     * the deferred stayed *pending* for the life of the process. `getCompleted` on a
     * pending deferred raises `IllegalStateException`, so one failed device-id read at
     * construction broke every [tick] and [tock] from then on — with a message naming
     * neither the provider nor the original cause.
     *
     * Failing is the right outcome when the node id is unavailable, because the
     * alternative is worse than an exception. The node id is half of the HLC string,
     * and two devices that both fell back to a placeholder would mint byte-identical
     * HLCs for concurrent edits — exactly the collision the [tick] doc describes, except
     * silent, and it would decide real data at the server. Completing exceptionally
     * keeps that loud, makes the cause diagnosable, and leaves the next call failing the
     * same deterministic way rather than flapping between outcomes.
     */
    private val nodeIdDeferred: Deferred<String> = CompletableDeferred<String>().also { deferred ->
        scope.launch {
            runCatchingCancellable { deviceIdProvider.getOrInitDeviceId() }
                .onSuccess(deferred::complete)
                .onFailure(deferred::completeExceptionally)
        }
    }

    // Last HLC, restored from storage if available.
    private var _lastHlc: Hlc? = null

    private val lastHlcDeferred: Deferred<Hlc> = CompletableDeferred<Hlc>().also { deferred ->
        scope.launch {
            runCatchingCancellable {
                hlcStore?.load() ?: Hlc.zero("pending")
            }.onSuccess(deferred::complete)
                .onFailure(deferred::completeExceptionally)
        }
    }

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
        val last = _lastHlc ?: lastHlcDeferred.getCompleted()
        Hlc.tick(last = last, node = node, nowMillis = currentTimeMillis()).also {
            _lastHlc = it
            persist(it)
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
        val local = _lastHlc ?: lastHlcDeferred.getCompleted()
        Hlc.tock(local = local, remote = remote, node = node, nowMillis = currentTimeMillis()).also {
            _lastHlc = it
            persist(it)
        }
    }

    /** Returns the last generated HLC. Guarded for the reason given on [tick]. */
    fun current(): Hlc = synchronized(this) {
        _lastHlc ?: runCatching { lastHlcDeferred.getCompleted() }
            .getOrElse { Hlc.zero("pending") }
    }

    private fun persist(hlc: Hlc) {
        val store = hlcStore ?: return
        scope.launch {
            store.save(hlc)
        }
    }

    private fun currentTimeMillis(): Long = clock.now().toEpochMilliseconds()
}
