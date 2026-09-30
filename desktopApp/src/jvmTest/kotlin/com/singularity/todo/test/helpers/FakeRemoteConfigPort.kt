package com.singularity.todo.test.helpers

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [RemoteConfigPort] for desktop UI tests.
 *
 * Serves [RemoteConfigSnapshot.defaults], which is exactly what the production
 * app falls back to when the network is unavailable. Two consequences the test
 * suite depends on:
 *
 * - `minSupportedVersion == null` → [AppVersionGateViewModel] evaluates to
 *   `Allowed`, so `AppVersionGateScreen` renders its content instead of the
 *   "Update Required" block screen.
 * - `whatsNewPayload == null` → `WhatsNewScreen` short-circuits and shows no
 *   bottom sheet over the shell.
 *
 * [seed] lets a test push a non-default snapshot, e.g. to exercise the blocked
 * gate.
 */
class FakeRemoteConfigPort(
    initial: RemoteConfigSnapshot = RemoteConfigSnapshot.defaults(),
) : RemoteConfigPort {

    private val _snapshot = MutableStateFlow(initial)

    /** Replaces the served snapshot; every `observe()` collector re-emits. */
    fun seed(snapshot: RemoteConfigSnapshot) {
        _snapshot.value = snapshot
    }

    override suspend fun snapshot(): RemoteConfigSnapshot = _snapshot.value

    override suspend fun refresh(): Result<RemoteConfigSnapshot> =
        Result.success(_snapshot.value)

    override fun observe(): StateFlow<RemoteConfigSnapshot> = _snapshot.asStateFlow()
}
