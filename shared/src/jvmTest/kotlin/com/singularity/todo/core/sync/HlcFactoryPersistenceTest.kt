package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class HlcFactoryPersistenceTest {
    private class InMemoryHlcStore : HlcStore {
        var stored: Hlc? = null

        override suspend fun load(): Hlc? = stored

        override suspend fun save(hlc: Hlc) {
            stored = hlc
        }
    }

    private fun newScope() = AutoCloseableCoroutineScope(Dispatchers.Unconfined + Job())

    /** A store that counts the writes, so "written once" is a fact and not a claim. */
    private class CountingHlcStore : HlcStore {
        var saves = 0
        var stored: Hlc? = null

        override suspend fun load(): Hlc? = stored

        override suspend fun save(hlc: Hlc) {
            saves++
            stored = hlc
        }
    }

    @Test
    fun `a new factory restores the persisted clock`() {
        val store = InMemoryHlcStore()
        val scope1 = newScope()
        val factory1 =
            HlcFactory(
                deviceIdProvider =
                    object : DeviceIdProvider {
                        override suspend fun getOrInitDeviceId(): String = "node-a"
                    },
                clock = MutableClock(),
                scope = scope1,
                hlcStore = store,
            )
        val first = factory1.tick()
        scope1.close()

        val scope2 = newScope()
        val factory2 =
            HlcFactory(
                deviceIdProvider =
                    object : DeviceIdProvider {
                        override suspend fun getOrInitDeviceId(): String = "node-a"
                    },
                clock = MutableClock(),
                scope = scope2,
                hlcStore = store,
            )
        val second = factory2.tick()
        assertTrue(second > first, "restored clock must keep monotonic order")
        scope2.close()
    }

    @Test
    fun `a store that cannot load falls back to zero`() {
        val store =
            object : HlcStore {
                override suspend fun load(): Hlc? = null

                override suspend fun save(hlc: Hlc) = Unit
            }
        val scope = newScope()
        val factory =
            HlcFactory(
                deviceIdProvider =
                    object : DeviceIdProvider {
                        override suspend fun getOrInitDeviceId(): String = "node-a"
                    },
                clock = MutableClock(),
                scope = scope,
                hlcStore = store,
            )
        assertEquals(0L, factory.current().physical)
        scope.close()
    }

    /**
     * A store whose **first** write waits for a gate.
     *
     * The gate is what puts the two writes in the order the defect needs. The clock
     * write of an ordinary tick is still in flight — holding the write lock — while the
     * batch writes the clock it minted, which is the *older* of the two by then.
     *
     * The gate applies to the first call only: the later calls are the ones that must
     * not be held back, because otherwise the test would be arranging a deadlock
     * rather than a race.
     */
    private class GatedHlcStore : HlcStore {
        val gate = CompletableDeferred<Unit>()

        /** Every clock the store was handed, in the order it was handed them. */
        val written = mutableListOf<Hlc>()

        var stored: Hlc? = null
            private set

        private var calls = 0

        override suspend fun load(): Hlc? = stored

        override suspend fun save(hlc: Hlc) {
            if (calls++ == 0) gate.await()
            written += hlc
            stored = hlc
        }
    }

    /**
     * A clock that is already written is never written backwards over.
     *
     * ## The defect this pins down
     *
     * The batch captured the clock under the monitor and persisted it *after* releasing
     * it. A tick running in that window minted a newer clock and started writing it —
     * and then the batch's older clock landed last and overwrote it. The stored clock
     * had moved **backwards**, so a restart restored a clock older than one the device
     * had already issued, and the next patch could carry a timestamp older than a
     * timestamp the server already held.
     *
     * The fix is that the stored clock only ever moves forward: the writes are
     * serialized, and a write that is not newer than the one already stored is
     * dropped.
     */
    @Test
    fun `a clock written while a batch is finishing is not rolled back by the batch`() =
        runTest {
            val store = GatedHlcStore()
            val scope = newScope()
            val factory =
                HlcFactory(
                    deviceIdProvider =
                        object : DeviceIdProvider {
                            override suspend fun getOrInitDeviceId(): String = "node-a"
                        },
                    clock = MutableClock(),
                    scope = scope,
                    hlcStore = store,
                )

            // An ordinary tick starts a slow write of the first clock.
            val first = factory.tick()

            // The batch mints the next clock and, at the end of its block, writes it.
            val batch = async { factory.batched { factory.tick() } }
            advanceUntilIdle()

            // The slow write lands first — it holds the write lock — and the batch's
            // write follows it.
            store.gate.complete(Unit)
            val batched = batch.await()

            assertTrue(batched > first, "the batch mints a newer clock")
            assertEquals(
                batched,
                store.stored,
                "the stored clock must be the newest one: the batch's older clock must " +
                    "not overwrite the newer one",
            )
            assertTrue(
                store.written.zipWithNext().all { (older, newer) -> older < newer },
                "the stored clock never moves backwards, but it was written as " +
                    "${store.written}",
            )

            scope.close()
        }

    /**
     * A tick minted by an unrelated call *during* a batch is not lost.
     *
     * The batch suppresses the per-tick clock writes, and the suppression is global:
     * a tick belonging to some other operation does not write the clock either. It is
     * still covered, because the batch writes the newest clock it knows about when it
     * ends, and the newest clock it knows about is the one that tick minted.
     */
    @Test
    fun `a tick minted by another thread during a batch is persisted`() =
        runTest {
            val store = CountingHlcStore()
            val scope = newScope()
            val factory =
                HlcFactory(
                    deviceIdProvider =
                        object : DeviceIdProvider {
                            override suspend fun getOrInitDeviceId(): String = "node-a"
                        },
                    clock = MutableClock(),
                    scope = scope,
                    hlcStore = store,
                )

            var foreign: Hlc? = null
            factory.batched {
                val worker = Thread { foreign = factory.tick() }.apply { isDaemon = true }
                worker.start()
                worker.join(30_000)
                factory.tick()
            }

            val minted = requireNotNull(foreign) { "the other thread did not mint a clock" }
            assertTrue(
                requireNotNull(store.stored) >= minted,
                "the clock of the tick minted during the batch must be persisted: " +
                    "stored=${store.stored}, minted=$minted",
            )

            scope.close()
        }

    /**
     * A batch writes the clock once, not once per tick.
     *
     * The clock is stored so that a restart does not mint a clock older than the one
     * already pushed, and writing it on every tick is the honest thing to do for one
     * patch at a time. It is ruinous for a batch: a profile wipe of ten thousand rows
     * mints ten thousand ticks, and ten thousand DataStore writes are ten thousand file
     * writes on a phone — which is what made the wipe take minutes.
     *
     * The ticks themselves are unchanged: still one per event, still distinct, still
     * ordered. Only the write is deferred, and it is deferred to the end of the batch,
     * so the stored clock is never older than the patches that carry it.
     */
    @Test
    fun `a batch mints many ticks and writes the clock once`() =
        runTest {
            val store = CountingHlcStore()
            val scope = newScope()
            val factory =
                HlcFactory(
                    deviceIdProvider =
                        object : DeviceIdProvider {
                            override suspend fun getOrInitDeviceId(): String = "node-a"
                        },
                    clock = MutableClock(),
                    scope = scope,
                    hlcStore = store,
                )

            val ticks = factory.batched { (1..10).map { factory.tick() } }

            assertEquals(10, ticks.size, "one tick per event")
            assertEquals(10, ticks.distinct().size, "the ticks stay distinct")
            assertEquals(1, store.saves, "the clock is written once for the whole batch")

            // Outside a batch the clock is written per tick, as it always was.
            factory.tick()
            assertEquals(2, store.saves, "a tick outside a batch writes the clock")

            scope.close()
        }
}
