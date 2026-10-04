package com.singularity.todo.update

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.config.UpdateStoreType

/**
 * Coordinates update offers across different app stores.
 *
 * Observes [RemoteConfigPort.observe] for [RemoteConfigSnapshot.updatePriority].
 * When the priority threshold is met and cooldown has expired, delegates to the
 * appropriate [UpdateStorePort] implementation based on
 * [RemoteConfigSnapshot.updateStoreType].
 *
 * For priority ≥ [SHOW_IMMEDIATELY_THRESHOLD]: immediate offer regardless of cooldown.
 * For priority < [SHOW_IMMEDIATELY_THRESHOLD]: cooldown of [COOLDOWN_DAYS] days applies.
 *
 * Call [tryOfferUpdate] from [Activity.onResume][android.app.Activity.onResume].
 *
 * ## Store selection
 * - [UpdateStoreType.GOOGLE_PLAY] → [GooglePlayUpdateStore]
 * - [UpdateStoreType.RUSTORE] → [RuStoreUpdateStore] (SDK stub; falls back to browser)
 * - [UpdateStoreType.SAMSUNG] → [DirectUrlUpdateStore] (browser fallback until SDK added)
 * - [UpdateStoreType.DIRECT_URL] → [DirectUrlUpdateStore] using [RemoteConfigSnapshot.updateStoreUrl]
 */
class AppUpdateGate(
    private val googlePlayStore: GooglePlayUpdateStore,
    private val ruStore: RuStoreUpdateStore,
    private val directUrlStore: DirectUrlUpdateStore,
    private val remoteConfigPort: RemoteConfigPort,
    private val prefs: AppUpdatePrefs,
) {

    companion object {
        const val SHOW_IMMEDIATELY_THRESHOLD = 4
        const val COOLDOWN_DAYS = 7L
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Checks the store and offers an update if appropriate.
     *
     * Must be called on the main thread. Internally uses [Handler.post] to
     * ensure the check runs on the main looper. For background callers, wrap
     * with `Dispatchers.Main.immediate` to avoid an extra dispatch.
     */
    fun tryOfferUpdateOnMain(activity: Activity) {
        handler.post {
            val offered = checkAndOfferUpdate(activity)
            if (offered) prefs.recordUpdateOffered()
        }
    }

    private fun checkAndOfferUpdate(activity: Activity): Boolean {
        val snapshot = remoteConfigPort.observe().value
        val priority = snapshot.updatePriority ?: return false

        val store = selectStore(snapshot, activity)
        val isAvailable = store.isUpdateAvailable(activity)

        val daysSinceLastOffer = prefs.daysSinceLastOffer()
        val isImmediate = priority >= SHOW_IMMEDIATELY_THRESHOLD
        val isCooldownExpired = daysSinceLastOffer >= COOLDOWN_DAYS

        if (!isImmediate && !isCooldownExpired) return false

        // For DIRECT_URL and RUSTORE (stub): offer unconditionally since isAvailable is always false
        if (!isAvailable && snapshot.updateStoreType == UpdateStoreType.GOOGLE_PLAY) return false

        return store.offerUpdate(activity)
    }

    private fun selectStore(
        snapshot: RemoteConfigSnapshot,
        activity: Activity,
    ): UpdateStorePort = when (snapshot.updateStoreType) {
        UpdateStoreType.GOOGLE_PLAY -> googlePlayStore

        UpdateStoreType.RUSTORE -> ruStore

        UpdateStoreType.SAMSUNG -> directUrlStore

        UpdateStoreType.DIRECT_URL -> {
            val url = snapshot.updateStoreUrl
                ?: "https://play.google.com/store/apps/details?id=${activity.packageName}"
            DirectUrlUpdateStore(activity.applicationContext, url)
        }
    }
}
