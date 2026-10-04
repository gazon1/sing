package com.singularity.todo.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * [UpdateStorePort] backed by RuStore's App Updates SDK.
 *
 * ## Setup
 * Add to `build.gradle.kts` (when ready):
 * ```kotlin
 * implementation("com.rustore.rustoreappupdate:rustoreappupdate:1.0.0")
 * ```
 *
 * Then replace [offerUpdate] implementation with the real SDK call:
 * ```kotlin
 * RustoreUpdateManager.getInstance(context).startUpdateFlow(...)
 * ```
 *
 * Until then, [offerUpdate] falls back to opening [DIRECT_UPDATE_URL] in a browser.
 */
class RuStoreUpdateStore(
    private val context: Context,
    private val directUpdateUrl: String = "https://rustore.ru/app/com.singularity.todo",
) : UpdateStorePort {

    override fun isUpdateAvailable(activity: Activity): Boolean {
        // TODO: replace with SDK call when rustore-appupdate dependency is added.
        // Example (pseudo-code):
        // val info = RustoreUpdateManager.getInstance(context).appUpdateInfo
        // return info.updateAvailability() == UPDATE_AVAILABLE && info.isFlexibleUpdateAllowed
        return false
    }

    override fun offerUpdate(activity: Activity): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(directUpdateUrl))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Throwable) {
        false
    }
}
