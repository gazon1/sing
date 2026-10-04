package com.singularity.todo.feature.gate.presentation.viewmodel

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState

/**
 * Intent for [AppVersionGateViewModel].
 *
 * [CheckAgain][AppVersionGateIntent.CheckAgain] re-reads remote config without
 * restarting the app, which is what the "Check again" button on the blocked screen
 * sends.
 */
sealed interface AppVersionGateIntent : MviIntent {
    data object CheckAgain : AppVersionGateIntent
}

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
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<AppVersionGateState, AppVersionGateIntent, Nothing>(
        initialState = AppVersionGateState.Checking,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    init {
        check()
    }

    /**
     * Fails **open**: a remote-config read that throws is reported and then evaluated against
     * the default snapshot, so a broken config server cannot leave every user stuck behind a
     * version gate they cannot pass. A hard failure here is the worst possible place to be
     * strict.
     */
    private fun check() {
        catchTo("Failed to read remote config", { evaluate(RemoteConfigSnapshot.defaults()) }) {
            runCatchingResult { evaluate(remoteConfigPort.snapshot()) }
        }
    }

    override fun onIntent(intent: AppVersionGateIntent) {
        when (intent) {
            is AppVersionGateIntent.CheckAgain -> {
                setState(AppVersionGateState.Checking)
                catchTo("Failed to refresh remote config", { evaluate(RemoteConfigSnapshot.defaults()) }) {
                    runCatchingResult {
                        val snapshot = remoteConfigPort.refresh()
                            // A returned failure is not a throw, so catchTo never sees it.
                            // It still has to reach the reporter.
                            .onFailure { crashReporter.report(it, REFRESH_FAILED) }
                            .getOrElse { RemoteConfigSnapshot.defaults() }
                        evaluate(snapshot)
                    }
                }
            }
        }
    }

    private fun evaluate(snapshot: RemoteConfigSnapshot) {
        val min = snapshot.minSupportedVersion
        setState(
            if (min != null && appVersion < min) {
                AppVersionGateState.Blocked(
                    minSupportedVersion = min,
                    currentVersion = appVersion,
                    updateUrl = playStoreUrl,
                )
            } else {
                AppVersionGateState.Allowed(snapshot)
            },
        )
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val REFRESH_FAILED = "gate.remote_config_refresh_failed"
    }
}
