package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Tag
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [HlcFactory] under concurrent callers.
 *
 * ## What this is a control for
 *
 * `tick` was an unsynchronized read-modify-write on `_lastHlc`, and `Hlc.tick` is pure. Two
 * threads entering together therefore both read the same value, both see the same physical
 * millisecond, both compute `counter + 1` — and return the **same HLC string**. One write is
 * silently lost.
 *
 * That collision is the defect rather than a side effect of it. The HLC is the ordering key
 * conflict resolution uses, so two edits carrying an identical timestamp have no order
 * between them, and which one wins stops being something the code decides. This matters more
 * now than it did: `supabase/migrations/2026-10-07-sync_schema.sql` carries `server_version`,
 * so the optimistic-concurrency path is being built directly on the ordering this produces.
 *
 * ## Why real threads and not the test scheduler
 *
 * Virtual time is single-threaded, so a coroutine-based version of this would pass against
 * the broken factory for the same reason a `delay`-based test proves nothing: the
 * interleaving it needs cannot happen. Plain JVM threads with a [CyclicBarrier] put the
 * callers in the window at the same time, which is the window the defect lives in.
 *
 * ## Why a fixed clock
 *
 * With the wall clock moving, a collision needs both threads to land in the same
 * millisecond — possible but not reliable. Pinned, uniqueness is carried entirely by the
 * counter, which is exactly the value the lost increment corrupts.
 */
@Tag("fast")
class HlcFactoryConcurrencyTest {

    private val threads = 8
    private val ticksPerThread = 250

    /**
     * A [SessionStore] that answers the one question [HlcFactory] asks and nothing else.
     *
     * The three write members are declared `= Unit` rather than as empty blocks so they do
     * not enter the `EmptyFunctionBlock` baseline. `FakeHlcFactory` is in that baseline for
     * the same no-op bodies; a new fixture does not need to join it.
     */
    private class FixedNodeStore : SessionStore {
        private val _deviceId = MutableStateFlow("node-fixed")
        override val accessToken: Flow<String?> = MutableStateFlow(null)
        override val refreshToken: Flow<String?> = MutableStateFlow(null)
        override val userEmail: Flow<String?> = MutableStateFlow(null)
        override val deviceId: StateFlow<String> = _deviceId

        override suspend fun getOrInitDeviceId(): String = _deviceId.value
        override suspend fun save(session: Session.SignedIn) = Unit
        override suspend fun saveDeviceId(id: String) = Unit
        override suspend fun clear() = Unit
    }

    /**
     * A scope carrying an explicit [Job] and dispatching unconfined.
     *
     * Two details, both forced by how [HlcFactory] reads its node id:
     *
     * - [AutoCloseableCoroutineScope.close] calls `cancel()` on its own context, and `cancel()`
     *   on a context with no [Job] throws rather than no-ops. `Dispatchers` alone is exactly
     *   that context, so without the `Job` teardown fails and the failure reads as a
     *   concurrency defect when the assertions underneath it passed.
     * - [HlcFactory] resolves the node id in a coroutine started from its constructor and reads
     *   it with `getCompleted()`, which throws while the deferred is pending. Unconfined
     *   dispatch runs that launch inline to completion during construction — the same reason
     *   `FakeHlcFactory` uses it — so the fixture is deterministic instead of racing the
     *   scheduler. The workers below are real threads either way; only this one-shot lookup
     *   needed help.
     */
    private fun newScope() = AutoCloseableCoroutineScope(Dispatchers.Unconfined + Job())

    /**
     * Builds a factory whose node id is already resolved, with `_lastHlc` carrying a physical
     * timestamp for the counter to advance from.
     *
     * The warm-up tick is deliberately not collected: it exists to resolve the deferred, not
     * to contribute an observation.
     */
    private fun warmedFactory(scope: AutoCloseableCoroutineScope): HlcFactory =
        HlcFactory(FixedNodeStore(), MutableClock(), scope).also { it.tick() }

    /**
     * Calls [HlcFactory.tick] from every thread at once and returns what each got back.
     *
     * The barrier is released once all threads have arrived, so the first read-modify-write
     * happens concurrently rather than by luck of scheduling.
     */
    private fun tickConcurrently(factory: HlcFactory): List<Hlc> {
        val barrier = CyclicBarrier(threads)
        val produced = ConcurrentLinkedQueue<Hlc>()
        val workers = (0 until threads).map {
            Thread {
                barrier.await()
                repeat(ticksPerThread) { produced += factory.tick() }
            }.apply { isDaemon = true }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }
        return produced.toList()
    }

    @Test
    fun `concurrent ticks never produce the same timestamp twice`() {
        val scope = newScope()
        try {
            val produced = tickConcurrently(warmedFactory(scope))

            assertEquals(
                threads * ticksPerThread,
                produced.size,
                "every thread must finish; a smaller count means one never produced, " +
                    "which is a different failure and should not read as a pass",
            )
            val duplicates = produced.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertEquals(
                emptyMap(),
                duplicates,
                "${duplicates.size} timestamp(s) were handed to more than one caller. Two " +
                    "edits carrying the same HLC have no order between them, so which one " +
                    "wins is decided by arrival rather than by the clock.",
            )
        } finally {
            scope.close()
        }
    }

    /**
     * The same collision across the two write paths, not just `tick`.
     *
     * A `tock` that loses its counter increment to a racing `tick` produces an HLC that was
     * already issued, which is the same ambiguity reached from the other direction: a remote
     * event merged concurrently with a local edit.
     */
    @Test
    fun `a tock racing a tick does not reissue a timestamp already handed out`() {
        val scope = newScope()
        try {
            val factory = warmedFactory(scope)
            val barrier = CyclicBarrier(threads)
            val produced = ConcurrentLinkedQueue<Hlc>()
            val remote = Hlc.zero("remote")

            val workers = (0 until threads).map { index ->
                Thread {
                    barrier.await()
                    repeat(ticksPerThread) { n ->
                        produced += if ((index + n) % 2 == 0) factory.tick() else factory.tock(remote)
                    }
                }.apply { isDaemon = true }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join(30_000) }

            assertEquals(threads * ticksPerThread, produced.size, "every thread must finish")
            val duplicates = produced.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertEquals(
                emptyMap(),
                duplicates,
                "tock and tick share one read-modify-write on _lastHlc; when both run at " +
                    "once, one of them reissues a timestamp the other already returned.",
            )
        } finally {
            scope.close()
        }
    }
}
