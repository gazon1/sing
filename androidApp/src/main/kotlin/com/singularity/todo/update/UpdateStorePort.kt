package com.singularity.todo.update

import android.app.Activity

/**
 * Port for offering app updates from an app store or distribution platform.
 *
 * Implementations are selected at runtime based on [com.singularity.todo.core.config.RemoteConfigSnapshot.updateStoreType].
 *
 * All implementations must be safe to call from the main thread.
 */
interface UpdateStorePort {

    /**
     * Returns true if an update is available from this store and the
     * current platform supports showing it.
     */
    fun isUpdateAvailable(activity: Activity): Boolean

    /**
     * Offers the update to the user (e.g. opens the store's update dialog).
     * Returns true if the offer was shown, false if not available or not allowed.
     *
     * For [com.singularity.todo.core.config.UpdateStoreType.DIRECT_URL],
     * this opens the browser with [android.content.Intent.ACTION_VIEW].
     */
    fun offerUpdate(activity: Activity): Boolean
}
