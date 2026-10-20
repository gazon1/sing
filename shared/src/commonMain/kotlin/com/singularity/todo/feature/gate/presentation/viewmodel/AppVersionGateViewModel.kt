package com.singularity.todo.feature.gate.presentation.viewmodel

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState
import kotlinx.coroutines.withTimeoutOrNull

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
 * @param readTimeoutMs Upper bound on one remote-config read, in milliseconds. The gate is a
 *   full-screen splash in front of the whole app, so a read that never comes back cannot be
 *   waited for indefinitely; see [check]. Defaults to [RemoteConfigPort.READ_TIMEOUT_MS] —
 *   the port bounds its own read by the same number, so the two layers cannot drift apart.
 * @param scope Coroutine scope for collecting [RemoteConfigPort.observe].
 */
class AppVersionGateViewModel(
    private val remoteConfigPort: RemoteConfigPort,
    private val appVersion: AppVersion,
    private val playStoreUrl: String,
    private val readTimeoutMs: Long = RemoteConfigPort.READ_TIMEOUT_MS,
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
     * A read that never *returns* is the same failure with a longer fuse. The state stays
     * [AppVersionGateState.Checking], and Checking is a full-screen splash in front of the whole
     * app — so a read that hangs takes the entire app down with it, not just the gate. Nothing
     * about a hang throws, so nothing about it reports itself: the app simply never starts. A
     * slow *local* read is enough to get there — a Room database created on first launch, a
     * locked multi-instance database. The read therefore has a deadline, [readTimeoutMs], and a
     * read that misses it is handled exactly like a read that failed: reported, breadcrumbed,
     * evaluated against the defaults.
     *
     * This deadline is the *outer* of two, and it is the one the UI sees. The port bounds its
     * own read as well ([RemoteConfigPort.READ_TIMEOUT_MS]), because a deadline here can only
     * end a read that honours cancellation — the port's bound is the one that survives a read
     * that blocks in a driver call. Both layers use the same number, so neither can drift.
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
            errorLabel = READ_FAILED,
            onError = { onReadFailed() },
            onBeforeReport = { recordBypass(it) },
        ) {
            runCatchingResult { evaluate(readWithinTimeout { remoteConfigPort.snapshot() }) }
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

    /**
     * The bypass itself. Kept separate so every failure path records it identically.
     *
     * The wording names the reason, because "the gate was let through" and "the gate was let
     * through because the read never came back" are two different investigations: the first
     * points at the config server, the second at whatever the read was waiting on.
     */
    private fun recordBypass(error: Throwable) {
        val reason = if (isReadTimeout(error)) TIMED_OUT_REASON else UNREADABLE_REASON
        crashReporter.addBreadcrumb("Version gate bypassed — $reason, admitted on defaults")
    }

    /**
     * Whether this failure is a missed deadline rather than a failed read.
     *
     * The code is the only thing that tells the two apart, and it has to survive into the
     * report: the timeout is the one failure the gate raises itself, so it raises it with a
     * code of its own.
     *
     * The port's own timeout code counts too. The port bounds its read as well, and when
     * that bound is the one that fires, the reason is still "the read did not come back" —
     * a record that said "the config could not be read" would point at the config server
     * when the cause was a slow disk.
     */
    private fun isReadTimeout(error: Throwable): Boolean {
        val code = (error as? AppError)?.code ?: return false
        return code == READ_TIMEOUT || code == RemoteConfigPort.READ_TIMEOUT_CODE
    }

    /**
     * Runs [read] under a deadline and turns a missed deadline into a failed read.
     *
     * A timeout is not a thrown error — the read simply never came back — so it is turned into
     * one here. [AppError.Network] carries a machine-shaped code, which is what lets the report
     * group timeouts apart from every other failed read and what lets the breadcrumb say which
     * reason the gate was let through for.
     *
     * This is the outer guard, and it is the one the UI sees: the port bounds its own read by
     * the same number ([RemoteConfigPort.READ_TIMEOUT_MS]), so a read that blocks in a driver
     * call is ended by the port even though this deadline could not end it.
     */
    private suspend fun <T : Any> readWithinTimeout(read: suspend () -> T): T =
        withTimeoutOrNull(readTimeoutMs) { read() }
            ?: throw AppError.Network(
                message = "Remote config read timed out after $readTimeoutMs ms",
                code = READ_TIMEOUT,
            )

    override fun onIntent(intent: AppVersionGateIntent) {
        when (intent) {
            is AppVersionGateIntent.CheckAgain -> {
                setState(AppVersionGateState.Checking)
                // A *returned* failure is not a throw, so it never reaches the funnel's error
                // arm on its own. `mapCatching` turns it into one, which is what puts it on
                // the same path as a thrown read — same grouping key, same ordering, one
                // place where a bypass can be recorded. It was previously reported and
                // breadcrumbmed by hand, which is exactly how the two drifted apart.
                //
                // The refresh is under the same deadline as the first read. This screen is a
                // full-screen splash too, and a refresh that never returns leaves the user on
                // `Checking` with nothing left to press but the button they already pressed.
                catchTo(
                    errorLabel = REFRESH_FAILED,
                    onError = { onReadFailed() },
                    onBeforeReport = { recordBypass(it) },
                ) {
                    val refreshed = readWithinTimeout { remoteConfigPort.refresh() }
                    refreshed.mapCatching { evaluate(it) }
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

        /**
         * Machine-shaped grouping key for a read that missed its deadline.
         *
         * Its own key, so a timeout is a row of its own in the report rather than one line
         * inside the general "the config could not be read" bucket: the two have different
         * causes and different fixes.
         */
        const val READ_TIMEOUT = "gate.remote_config_read_timeout"

        /** The read failed outright. */
        const val UNREADABLE_REASON = "remote config unreadable"

        /** The read did not come back in time. */
        const val TIMED_OUT_REASON = "remote config read timed out"

        /** The label a failed read is reported under; the fallback grouping key. */
        const val READ_FAILED = "Failed to read remote config"
    }
}
