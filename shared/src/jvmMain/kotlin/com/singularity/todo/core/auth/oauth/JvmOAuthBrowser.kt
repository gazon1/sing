package com.singularity.todo.core.auth.oauth

import com.singularity.todo.core.error.runCatchingCancellable
import java.awt.Desktop
import java.net.URI

/**
 * Opens the consent page in the desktop's default browser.
 *
 * A headless machine has no browser and no display: `Desktop.isDesktopSupported`
 * says so up front, and the caller reports the failure instead of waiting for
 * a redirect that cannot come.
 */
class JvmOAuthBrowser : OAuthBrowser {

    /**
     * Whether this machine has a browser to open.
     *
     * The answer is computed and it is guarded: `Desktop.getDesktop()` throws on a
     * machine with no display, and a question that throws is not an answer. A
     * headless host says no here, and the caller disables the button rather than
     * failing after the user pressed it.
     */
    override fun isAvailable(): Boolean = runCatchingCancellable {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
    }.getOrDefault(false)

    override suspend fun open(url: String): Boolean = runCatchingCancellable {
        if (!Desktop.isDesktopSupported()) {
            false
        } else {
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                false
            } else {
                // `browse` hands the URL to the desktop and returns nothing: the
                // browser is the one that opens, not this call. An IOException here
                // means there is no browser to hand it to.
                desktop.browse(URI(url))
                true
            }
        }
    }.getOrDefault(false)
}
