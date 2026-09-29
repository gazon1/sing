package com.singularity.todo.core.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Android implementation of [SharePort] — `ACTION_SEND` with `text/plain` behind a
 * system chooser.
 *
 * The chooser is mandatory here rather than an optimisation: `startActivity` on a bare
 * `ACTION_SEND` resolves against whichever app the system picks, which varies by OEM and
 * Android version. `createChooser` makes the user the chooser, which is both predictable
 * and what a "Share" affordance is expected to look like.
 */
class AndroidSharePort(private val context: Context) : SharePort {

    override fun shareText(title: String, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, title)
        // Launched from a Composable, so the caller is (transitively) an Activity and the
        // share lands in the back stack when the target app finishes.
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            // No app claims text/plain — on a stock device this is near-impossible, but a
            // stripped AOSP image or work profile can get here. Reporting false lets the
            // caller show a message instead of crashing.
            false
        }
    }
}
