package com.singularity.todo.update

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType

/**
 * [UpdateStorePort] backed by Google Play's In-App Updates API.
 *
 * Uses [AppUpdateManager] with a flexible update flow. The flexible flow
 * allows the user to continue using the app while the update downloads in the
 * background, then prompts them to restart to apply.
 *
 * Requires the app to be distributed via Google Play to receive update info.
 */
class GooglePlayUpdateStore(
    private val appUpdateManager: AppUpdateManager,
) : UpdateStorePort {

    companion object {
        private const val UPDATE_AVAILABLE = 1
        private const val START_UPDATE_RESULT_AVAILABLE = 1
    }

    override fun isUpdateAvailable(activity: Activity): Boolean {
        val info = runCatching { appUpdateManager.appUpdateInfo.result }.getOrNull() ?: return false
        if (info.updateAvailability() != UPDATE_AVAILABLE) return false
        return runCatching { info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) }.getOrDefault(false)
    }

    override fun offerUpdate(activity: Activity): Boolean {
        val info = runCatching { appUpdateManager.appUpdateInfo.result }.getOrNull() ?: return false
        if (info.updateAvailability() != UPDATE_AVAILABLE) return false

        val allowed = runCatching { info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) }.getOrDefault(false)
        if (!allowed) return false

        val options = AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()

        @Suppress("MissingPermission")
        return runCatching {
            appUpdateManager.startUpdateFlow(info, activity, options).result
        }.getOrNull() == START_UPDATE_RESULT_AVAILABLE
    }
}
