package com.singularity.todo.feature.gate.presentation.viewmodel

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.mvi.MviIntent
import com.singularity.todo.core.ui.mvi.MviViewModel
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState
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
sealed interface AppVersionGateIntent : MviIntent {
    data object CheckAgain : AppVersionGateIntent
}

class AppVersionGateViewModel(
    private val remoteConfigPort: RemoteConfigPort,
    private val appVersion: AppVersion,
    private val playStoreUrl: String,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<AppVersionGateState, AppVersionGateIntent, Nothing>(
    initialState = AppVersionGateState.Checking,
    scope = scope,
) {

    init { addCloseable(scope) }
    init { check() }

    private fun check() {
        scope.launch {
            val snapshot = remoteConfigPort.snapshot()
            evaluate(snapshot)
        }
    }

    override fun onIntent(intent: AppVersionGateIntent) {
        when (intent) {
            is AppVersionGateIntent.CheckAgain -> {
                __state.value = AppVersionGateState.Checking
                scope.launch {
                    val result = remoteConfigPort.refresh()
                    val snapshot = result.getOrElse { RemoteConfigSnapshot.defaults() }
                    evaluate(snapshot)
                }
            }
        }
    }

    private fun evaluate(snapshot: RemoteConfigSnapshot) {
        val min = snapshot.minSupportedVersion
        __state.value = if (min != null && appVersion < min) {
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
