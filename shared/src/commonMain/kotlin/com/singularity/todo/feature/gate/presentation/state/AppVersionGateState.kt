package com.singularity.todo.feature.gate.presentation.state

import com.singularity.todo.core.version.AppVersion

/**
 * UI state for the [AppVersionGateScreen][com.singularity.todo.feature.gate.presentation.screen.AppVersionGateScreen].
 *
 * [AppVersionGateState.Checking] is transient — the screen should transition
 * to [Allowed] or [Blocked] within the same composition, so no loading UI is shown.
 */
sealed interface AppVersionGateState {
    /** Version check in progress — shows a minimal splash. */
    data object Checking : AppVersionGateState

    /**
     * Version gate passed. App may proceed to [AuthGuard] and the main [Nav3State].
     *
     * @param config The validated remote config snapshot (may be the default/empty one).
     */
    data class Allowed(val config: com.singularity.todo.core.config.RemoteConfigSnapshot) : AppVersionGateState

    /**
     * App version is below [minSupportedVersion]. User must update before using the app.
     *
     * @param minSupportedVersion The minimum version required by the server.
     * @param currentVersion The user's current app version.
     * @param updateUrl Play Store URL (Android) or releases page URL (Desktop).
     */
    data class Blocked(val minSupportedVersion: AppVersion, val currentVersion: AppVersion, val updateUrl: String) :
        AppVersionGateState
}
