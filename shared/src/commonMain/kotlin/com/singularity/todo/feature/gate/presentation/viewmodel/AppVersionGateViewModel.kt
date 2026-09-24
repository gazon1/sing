package com.singularity.todo.feature.gate.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [AppVersionGateScreen][com.singularity.todo.feature.gate.presentation.screen.AppVersionGateScreen].
 *
 * Observes [RemoteConfigPort.observe] and compares [RemoteConfigSnapshot.minSupportedVersion]
 * against the current [appVersion]. If the version is below minimum, emits [AppVersionGateState.Blocked].
 * Otherwise emits [AppVersionGateState.Allowed].
 *
 * The Play Store / releases URL is passed at construction time because it differs
 * between Android (`market://details?id=…`) and Desktop (GitHub releases page).
 *
 * @param remoteConfigPort The remote config repository.
 * @param appVersion The current app version (injected from platform-specific [appVersion]).
 * @param playStoreUrl Platform-specific URL to open for update (Play Store on Android,
 *   releases page on Desktop). Used in the [AppVersionGateState.Blocked] state.
 * @param scope Coroutine scope for collecting [RemoteConfigPort.observe].
 */
class AppVersionGateViewModel(
    private val remoteConfigPort: RemoteConfigPort,
    private val appVersion: AppVersion,
    private val playStoreUrl: String,
    private val scope: AutoCloseableCoroutineScope,
) : ViewModel() {

    init {
        addCloseable(scope)
        check()
    }

    private val _state = MutableStateFlow<AppVersionGateState>(AppVersionGateState.Checking)
    val state: StateFlow<AppVersionGateState> = _state.asStateFlow()

    /**
     * Re-check the gate using a fresh [RemoteConfigPort.refresh] call.
     * Called by the "Check Again" button on the blocked screen.
     */
    fun onCheckAgain() {
        viewModelScope.launch {
            _state.value = AppVersionGateState.Checking
            val result = remoteConfigPort.refresh()
            val snapshot = result.getOrElse { RemoteConfigSnapshot.defaults() }
            evaluate(snapshot)
        }
    }

    private fun check() {
        viewModelScope.launch {
            val snapshot = remoteConfigPort.snapshot()
            evaluate(snapshot)
        }
    }

    private fun evaluate(snapshot: RemoteConfigSnapshot) {
        val min = snapshot.minSupportedVersion
        _state.value = if (min != null && appVersion < min) {
            AppVersionGateState.Blocked(
                minSupportedVersion = min,
                currentVersion = appVersion,
                updateUrl = playStoreUrl,
            )
        } else {
            AppVersionGateState.Allowed(snapshot)
        }
    }
}
