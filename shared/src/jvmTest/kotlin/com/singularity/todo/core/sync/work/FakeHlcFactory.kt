package com.singularity.todo.core.sync.work

import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.sync.HlcFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal fake [HlcFactory] for tests.
 *
 * Note: [Clock] is an expect object — in jvmTest we pass the actual [Clock] singleton,
 * which is safe since it is immutable and stateless.
 * [SessionStore] is implemented as a minimal object — the [SyncEngine] only calls
 * [SessionStore.getOrInitDeviceId] inside [HlcFactory]'s init block, and this is
 * called before our test's [advanceUntilIdle] returns.
 */
class FakeHlcFactory :
    HlcFactory(
        sessionStore = object : SessionStore {
            private val _deviceId = MutableStateFlow("test-node")
            override val accessToken: Flow<String?> = MutableStateFlow(null)
            override val refreshToken: Flow<String?> = MutableStateFlow(null)
            override val userEmail: Flow<String?> = MutableStateFlow(null)
            override val deviceId: StateFlow<String> = _deviceId

            override suspend fun getOrInitDeviceId(): String = _deviceId.value
            override suspend fun save(session: com.singularity.todo.core.auth.Session.SignedIn) {}
            override suspend fun saveDeviceId(id: String) {}
            override suspend fun clear() {}
        },
        clock = Clock, // actual singleton — safe to share in tests
        scope = AutoCloseableCoroutineScope(CoroutineScope(Dispatchers.Unconfined).coroutineContext),
    )
