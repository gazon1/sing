package com.singularity.todo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import androidx.navigation3.runtime.deeplink.invoke
import com.singularity.todo.feature.nav.deeplink.DeepLinkRegistry
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import com.singularity.todo.update.AppUpdateGate
import org.koin.android.ext.android.inject

/**
 * Main (and only) Activity.
 *
 * Koin is started once in [SingularityApp.onCreate] — process-scoped,
 * guaranteed single initialization before any Activity or Service.
 */
class MainActivity : ComponentActivity() {

    private val appUpdateGate: AppUpdateGate by inject()

    /**
     * The deep-link registry — the single place that knows which links the app
     * understands and what screen each of them opens.
     */
    private val deepLinkRegistry: DeepLinkRegistry by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val target = resolveDeepLink(intent)

        setContent {
            App(deepLinkTarget = target)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Nothing to do for an intent that carries no known link: the app stays
        // where it is. Re-setting the content in that case would throw away the
        // current navigation state for no reason.
        val target = resolveDeepLink(intent) ?: return
        setContent {
            App(deepLinkTarget = target)
        }
    }

    /**
     * Resolves the intent into a deep-link target.
     *
     * The intent is not parsed here: the registry holds one matcher per link, and
     * each matcher owns its own URI grammar. An intent that carries no data and no
     * extras cannot become a [DeepLinkRequest] at all (the official type requires
     * one of the two), and a URI that matches no registered link resolves to
     * `null` — both are ordinary outcomes, not errors.
     *
     * @param intent the incoming intent, possibly `null`.
     * @return the target to open, or `null` when the intent carries no known link.
     */
    private fun resolveDeepLink(intent: Intent?): DeepLinkTarget? {
        if (intent == null) return null
        val request = runCatching { DeepLinkRequest(intent) }.getOrNull() ?: return null
        return runCatching { deepLinkRegistry.resolve(request) }.getOrNull()
    }

    override fun onResume() {
        super.onResume()
        appUpdateGate.tryOfferUpdateOnMain(this)
    }
}
