package com.singularity.todo.update

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play In-App Update gate.
 *
 * Observes [RemoteConfigPort.observe] for [RemoteConfigSnapshot.updatePriority].
 * When priority ≥ [SHOW_IMMEDIATELY_THRESHOLD] and Play Core reports an available
 * flexible update, offers the update via [AppUpdateManager.startUpdateFlow].
 *
 * For lower priorities, the offer is rate-limited: not re-offered within
 * [COOLDOWN_DAYS] days of the last offer.
 *
 * Call [tryOfferUpdate] from [Activity.onResume][android.app.Activity.onResume].
 */
class AppUpdateGate(
    private val appUpdateManager: AppUpdateManager,
    private val remoteConfigPort: RemoteConfigPort,
    private val prefs: AppUpdatePrefs,
) {
    companion object {
        /** Priority threshold that triggers immediate offer regardless of cooldown. */
        const val SHOW_IMMEDIATELY_THRESHOLD = 4

        /** Days between re-offering the same update to the user. */
        const val COOLDOWN_DAYS = 7L
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Checks the Play Core API and offers a flexible update if appropriate.
     * Call from [Activity.onResume][android.app.Activity.onResume].
     */
    fun tryOfferUpdate(activity: Activity) {
        handler.post {
            val offered = checkAndOfferUpdate(activity)
            if (offered) prefs.recordUpdateOffered()
        }
    }

    private fun checkAndOfferUpdate(activity: Activity): Boolean {
        val snapshot = remoteConfigPort.observe().value
        val priority = snapshot.updatePriority ?: return false

        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        val updateInfo = appUpdateInfoTask.result ?: return false

        // UpdateAvailability: 0 = NOT_AVAILABLE, 1 = AVAILABLE, 2 = DEVELOPER_TRIGGERED
        val availability = updateInfo.updateAvailability()
        if (availability != 1) return false

        // Check flexible update is allowed (isUpdateTypeAllowed takes AppUpdateType int value)
        val flexibleAllowed = try {
            updateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
        } catch (e: Throwable) {
            false
        }
        if (!flexibleAllowed) return false

        val daysSinceLastOffer = prefs.daysSinceLastOffer()
        val isImmediate = priority >= SHOW_IMMEDIATELY_THRESHOLD
        val isCooldownExpired = daysSinceLastOffer >= COOLDOWN_DAYS

        if (!isImmediate && !isCooldownExpired) return false

        val options = AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()

        @Suppress("MissingPermission")
        val resultTask = appUpdateManager.startUpdateFlow(updateInfo, activity, options)

        // Result: 1 = START_UPDATE_RESULT_AVAILABLE, 2 = IN_COMPATIBILITY_CHECK_MODE
        return try {
            resultTask.result == 1
        } catch (e: Throwable) {
            false
        }
    }
}

/**
 * Lightweight preferences for update gating, backed by SharedPreferences.
 *
 * SharedPreferences (not DataStore) is intentional:
 * - Only 2 fields, no transactions needed
 * - Already available as a transitive dependency of the Play Core library
 */
class AppUpdatePrefs private constructor(
    private val prefs: android.content.SharedPreferences,
) {

    /** Returns the epoch millis of the last update offer, or 0 if never. */
    fun lastOfferedAt(): Long = prefs.getLong(KEY_LAST_OFFERED_AT, 0L)

    /** Days elapsed since the last offer. Returns [Long.MAX_VALUE] if never offered. */
    fun daysSinceLastOffer(): Long {
        val last = lastOfferedAt()
        if (last == 0L) return Long.MAX_VALUE
        val now = System.currentTimeMillis()
        return java.util.concurrent.TimeUnit.MILLISECONDS.toDays(now - last)
    }

    /** Records that an update offer was made. */
    fun recordUpdateOffered() {
        prefs.edit().putLong(KEY_LAST_OFFERED_AT, System.currentTimeMillis()).apply()
    }

    /** Clears the cooldown, forcing the next eligible update to show immediately. */
    fun clearCooldown() {
        prefs.edit().remove(KEY_LAST_OFFERED_AT).apply()
    }

    companion object {
        private const val KEY_LAST_OFFERED_AT = "update_last_offered_at"
        private const val PREFS_FILE = "app_update_prefs"

        fun create(context: Context): AppUpdatePrefs {
            return AppUpdatePrefs(
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            )
        }
    }
}
