package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    @Test
    fun `a new factory restores the persisted clock`() {
        val store = InMemoryHlcStore()
        val scope1 = newScope()
        val factory1 = HlcFactory(
            deviceIdProvider = object : DeviceIdProvider {
                override suspend fun getOrInitDeviceId(): String = "node-a"
            },
            clock = MutableClock(),
            scope = scope1,
            hlcStore = store,
        )
        val first = factory1.tick()
        scope1.close()

        val scope2 = newScope()
        val factory2 = HlcFactory(
            deviceIdProvider = object : DeviceIdProvider {
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
        val store = object : HlcStore {
            override suspend fun load(): Hlc? = null
            override suspend fun save(hlc: Hlc) = Unit
        }
        val scope = newScope()
        val factory = HlcFactory(
            deviceIdProvider = object : DeviceIdProvider {
                override suspend fun getOrInitDeviceId(): String = "node-a"
            },
            clock = MutableClock(),
            scope = scope,
            hlcStore = store,
        )
        assertEquals(0L, factory.current().physical)
        scope.close()
    }
}
