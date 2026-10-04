package com.singularity.todo.feature.gate.presentation.viewmodel

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
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
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
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
     *
     * The bypass is also breadcrumbed, because failing open is otherwise invisible: the end
     * state is `Allowed(defaults)`, which is byte-for-byte what a healthy read produces. A
     * config outage would read as a healthy dashboard for as long as it lasted. The report
     * says the read failed; only the breadcrumb says the gate was let through anyway.
     *
     * The breadcrumb goes through `onBeforeReport`, not `onError`, and the distinction is the
     * whole point. The backend attaches the breadcrumb buffer to a report as it stands when the
     * report is made, so a record written from `onError` is attached to the *next* event — and
     * during a config outage there is no next event, which is exactly the silence the record
     * exists to prevent.
     */
    private fun check() {
        catchTo(
            errorLabel = "Failed to read remote config",
            onError = { onReadFailed() },
            onBeforeReport = { recordBypass() },
        ) {
            runCatchingResult { evaluate(remoteConfigPort.snapshot()) }
        }
    }

    /**
     * Admits on the default snapshot, then does exactly that.
     *
     * The breadcrumb is already recorded by the time this runs: [check] passes it to
     * `onBeforeReport`, so the record rides on the report rather than trailing it. This
     * function is only the *decision* to continue, which is the part that has no ordering
     * requirement.
     */
    private fun onReadFailed() {
        evaluate(RemoteConfigSnapshot.defaults())
    }

    /** The bypass itself. Kept separate so every failure path records it identically. */
    private fun recordBypass() {
        crashReporter.addBreadcrumb("Version gate bypassed — remote config unreadable, admitted on defaults")
    }

    override fun onIntent(intent: AppVersionGateIntent) {
        when (intent) {
            is AppVersionGateIntent.CheckAgain -> {
                setState(AppVersionGateState.Checking)
                // A *returned* failure is not a throw, so it never reaches the funnel's error
                // arm on its own. `mapCatching` turns it into one, which is what puts it on
                // the same path as a thrown read — same grouping key, same ordering, one
                // place where a bypass can be recorded. It was previously reported and
                // breadcrumbmed by hand, which is exactly how the two drifted apart.
                catchTo(
                    errorLabel = REFRESH_FAILED,
                    onError = { onReadFailed() },
                    onBeforeReport = { recordBypass() },
                ) {
                    remoteConfigPort.refresh().mapCatching { evaluate(it) }
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
