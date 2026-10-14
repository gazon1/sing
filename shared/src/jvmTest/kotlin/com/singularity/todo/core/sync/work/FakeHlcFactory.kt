package com.singularity.todo.core.sync.work

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.sync.DeviceIdProvider
import com.singularity.todo.core.sync.HlcFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock

/**
 * Minimal fake [HlcFactory] for tests.
 *
 * Note: [Clock] is an expect object — in jvmTest we pass the actual [Clock] singleton,
 * which is safe since it is immutable and stateless.
 * [DeviceIdProvider] is implemented as a minimal object — the [SyncEngine] only calls
 * [DeviceIdProvider.getOrInitDeviceId] inside [HlcFactory]'s init block, and this is
 * called before our test's [advanceUntilIdle] returns.
 */
class FakeHlcFactory :
    HlcFactory(
        deviceIdProvider = object : DeviceIdProvider {
            override suspend fun getOrInitDeviceId(): String = "test-node"
        },
        clock = Clock.System, // actual singleton — safe to share in tests
        scope = AutoCloseableCoroutineScope(CoroutineScope(Dispatchers.Unconfined).coroutineContext),
    )
