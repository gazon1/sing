package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val nodeIdDeferred: Deferred<String> =
        CompletableDeferred<String>().also { deferred ->
            scope.launch {
                runCatchingCancellable { deviceIdProvider.getOrInitDeviceId() }
                    .onSuccess(deferred::complete)
                    .onFailure(deferred::completeExceptionally)
            }
        }

    // Last HLC, restored from storage if available.
    private var _lastHlc: Hlc? = null

    /**
     * How many [batched] blocks are open right now.
     *
     * Guarded by the same monitor as [_lastHlc], because [tick] reads it under that
     * monitor too.
     */
    private var batchDepth = 0

    /**
     * Serializes the clock writes.
     *
     * ## Why the writes need a lock of their own
     *
     * The clock is written from two kinds of place: the batch writes it once, when the
     * block ends, and every ordinary tick writes it as it goes. Those writes can
     * overlap — the batch's write happens after it released the monitor that guards
     * [_lastHlc], so a tick can mint a newer clock and start writing it while the
     * batch's write is still on its way.
     *
     * Without serialization the order of the two writes is arbitrary, and the losing
     * order is the dangerous one: the batch holds the *older* clock, so when its write
     * lands last the stored clock moves **backwards**. A restart then restores a clock
     * older than one already pushed, and the next patch can carry a timestamp older
     * than a timestamp the server already holds — which is exactly the ordering the
     * HLC exists to provide.
     */
    private val clockWriteMutex = Mutex()

    /**
     * The newest clock already handed to the store.
     *
     * Guarded by the same monitor as [_lastHlc].
     *
     * The mutex alone orders the writes, but ordering is not enough: a write that was
     * minted earlier can still reach the store later. This is what makes that
     * impossible — a clock that is not newer than the one already written is dropped
     * rather than written, so the stored clock only ever moves forward.
     */
    private var writtenHlc: Hlc? = null

    private val lastHlcDeferred: Deferred<Hlc> =
        CompletableDeferred<Hlc>().also { deferred ->
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
     * server saw first. Now that `infra/supabase/migrations/` carries `server_version` and the
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
    fun tick(): Hlc {
        var write = false
        val minted =
            synchronized(this) {
                val node = nodeIdDeferred.getCompleted()
                val last = _lastHlc ?: lastHlcDeferred.getCompleted()
                Hlc.tick(last = last, node = node, nowMillis = currentTimeMillis()).also {
                    _lastHlc = it
                    // Inside a batch the clock is persisted once, at the end: a profile wipe
                    // mints one tick per removed row, and a DataStore write per tick is what
                    // made the wipe of ten thousand rows take minutes.
                    //
                    // The suppression is global, so a tick minted by an *unrelated* call
                    // during the batch is not written by itself either. It is not lost: the
                    // batch writes the newest clock it knows about when it ends, and the
                    // newest clock it knows about is the one that tick minted — both are
                    // read under this same monitor, so the tick is either counted in the
                    // batch's write or sees an empty batch and writes itself.
                    write = batchDepth == 0
                }
            }
        // Outside the monitor on purpose: the write waits for [clockWriteMutex], and a
        // writer holding that mutex needs this monitor to check [writtenHlc]. Holding
        // the monitor while waiting for the mutex would be a deadlock.
        if (write) persist(minted)
        return minted
    }

    /**
     * Mints ticks without persisting each one, and persists the last one when the block
     * ends.
     *
     * ## Why the persistence is deferred
     *
     * The clock is stored so that a restart does not mint a clock older than the one
     * already pushed. Writing it on every tick is honest for one patch at a time and
     * ruinous for a batch: a wipe of ten thousand rows mints ten thousand ticks, and
     * ten thousand DataStore writes are ten thousand file writes on a phone.
     *
     * The batch still persists — once, with the last clock minted — so the property the
     * store exists for survives. What it stops doing is writing the same file ten
     * thousand times to record the same fact.
     *
     * The ticks themselves are unchanged: each one is still distinct and still ordered.
     *
     * ## When the write happens
     *
     * The clock is written when the block ends, which is **before** the patches that
     * carry it are queued. A crash therefore leaves the clock ahead of the queue rather
     * than behind it: a clock that skipped ahead loses nothing, and a clock that lagged
     * behind could mint a patch older than one the server already holds.
     *
     * ## What the write does not do
     *
     * It does not overwrite a newer clock. A tick minted by another call while the
     * batch was running may well have written its clock already, and the batch's clock
     * is the older of the two by then; writing it would move the stored clock
     * backwards. [writeClock] drops it instead.
     *
     * ## Why the write survives a cancellation
     *
     * The write is the one thing in the batch that must not be lost, because the batch
     * suppressed the per-tick writes: a tick minted by an unrelated call during the
     * batch did not write its own clock either, and this write is the only one that
     * carries it.
     *
     * A `finally` of a cancelled coroutine runs in a cancelled context, where a
     * suspending call throws before it does anything — so without [NonCancellable] the
     * write would be skipped exactly when the batch was abandoned, and the stored
     * clock would stay older than a clock the device already issued. The write is
     * therefore not cancellable. It is a single local file write, so it cannot hold
     * the cancellation for long.
     */
    suspend fun <T> batched(block: () -> T): T {
        synchronized(this) { batchDepth++ }
        try {
            return block()
        } finally {
            val last =
                synchronized(this) {
                    batchDepth--
                    if (batchDepth == 0) _lastHlc else null
                }
            last?.let { withContext(NonCancellable) { writeClock(it) } }
        }
    }

    /**
     * Writes the clock and waits for it to land.
     *
     * The suspending form of [persist], for the places where the write has to be
     * finished before the caller continues.
     */
    private suspend fun writeClock(hlc: Hlc) {
        val store = hlcStore ?: return
        clockWriteMutex.withLock {
            val written = synchronized(this) { writtenHlc }
            if (written != null && hlc <= written) return
            store.save(hlc)
            synchronized(this) { writtenHlc = hlc }
        }
    }

    /**
     * Merges a received remote HLC with the local state.
     *
     * Synchronized for the same reason as [tick]: `tock` is a read-modify-write on
     * `_lastHlc`, and a `tock` racing a `tick` loses a counter increment the same way.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tock(remote: Hlc): Hlc {
        val merged =
            synchronized(this) {
                val node = nodeIdDeferred.getCompleted()
                val local = _lastHlc ?: lastHlcDeferred.getCompleted()
                Hlc.tock(local = local, remote = remote, node = node, nowMillis = currentTimeMillis()).also {
                    _lastHlc = it
                }
            }
        // Outside the monitor, for the reason [tick] gives.
        persist(merged)
        return merged
    }

    /** Returns the last generated HLC. Guarded for the reason given on [tick]. */
    fun current(): Hlc =
        synchronized(this) {
            _lastHlc ?: runCatching { lastHlcDeferred.getCompleted() }
                .getOrElse { Hlc.zero("pending") }
        }

    /**
     * Queues a clock write without waiting for it.
     *
     * The write itself goes through [writeClock], so it is serialized with the other
     * writes and cannot move the stored clock backwards.
     */
    private fun persist(hlc: Hlc) {
        if (hlcStore == null) return
        scope.launch {
            writeClock(hlc)
        }
    }

    private fun currentTimeMillis(): Long = clock.now().toEpochMilliseconds()
}
