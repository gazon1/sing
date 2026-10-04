package com.singularity.todo.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * [UpdateStorePort] that opens a custom URL in a browser.
 *
 * Used when no in-app update SDK is available (e.g. F-Droid, Amazon Appstore,
 * or a direct APK distribution). The URL typically points to the app's page
 * on the relevant store.
 *
 * Since there is no SDK to query for update availability, [isUpdateAvailable]
 * always returns false — callers should fall back to [offerUpdate] which
 * opens the store page unconditionally.
 */
class DirectUrlUpdateStore(private val context: Context, private val storeUrl: String) : UpdateStorePort {

    /**
     * Always returns false — there is no programmatic way to check for updates
     * without an SDK. Use [offerUpdate] to open the store page instead.
     */
    override fun isUpdateAvailable(activity: Activity): Boolean = false

    /**
     * Opens [storeUrl] in a browser via [Intent.ACTION_VIEW].
     * The user can then manually trigger the update from the store page.
     */
    override fun offerUpdate(activity: Activity): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Throwable) {
        false
    }
}
