package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
