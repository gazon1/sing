package com.singularity.todo.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.singularity.todo.core.links.InternalLinks

/**
 * The app's URI handler: internal links stay inside, everything else leaves.
 *
 * ## Why this is a wrapper and not the platform handler
 *
 * The platform handler opens an address in a browser. An internal link — `note://`,
 * `task://` — has no meaning outside the app, and handing one to the platform
 * produces either an error dialog or a browser search for the string. So the address
 * is read first: if it is one of ours, the app opens it; if it is not, the platform
 * does what it always did.
 *
 * The same rule applies to a link inside a piece of text, which is why the handler
 * is offered as a component rather than only as a function.
 *
 * @param onInternalLink what to do with an internal link. Defaults to the handler
 *   provided by [LocalInternalLinkHandler].
 * @return a handler to pass to a link, or to open an address with.
 */
@Composable
fun rememberAppUriHandler(
    onInternalLink: InternalLinkHandler = LocalInternalLinkHandler.current,
): UriHandler {
    val platformHandler = LocalUriHandler.current
    val handler by rememberUpdatedState(onInternalLink)
    return remember(platformHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val target = InternalLinks.parse(uri)
                if (target != null) {
                    handler(TextLink.Internal(target))
                } else {
                    platformHandler.openUri(uri)
                }
            }
        }
    }
}
