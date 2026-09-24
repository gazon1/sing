package com.singularity.todo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.singularity.todo.core.notifications.AndroidNotifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val (deeplinkTaskId, deeplinkViewId) = resolveDeepLinkIntents(intent)

        setContent {
            App(deeplinkViewId = deeplinkViewId, deeplinkTaskId = deeplinkTaskId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val (deeplinkTaskId, deeplinkViewId) = resolveDeepLinkIntents(intent)
        if (deeplinkTaskId != null || deeplinkViewId != null) {
            setContent {
                App(deeplinkViewId = deeplinkViewId, deeplinkTaskId = deeplinkTaskId)
            }
        }
    }

    /**
     * Extracts navigation targets from two independent sources:
     * 1. `intent.data` — `singularity://task/{taskId}` from calendar event taps → deeplinkTaskId
     * 2. `AndroidNotifier.EXTRA_DEEPLINK_VIEW_ID` — from notification taps → deeplinkViewId
     *
     * These are independent, so both can be non-null if both intent extras are somehow set.
     */
    private fun resolveDeepLinkIntents(intent: Intent?): Pair<String?, String?> {
        if (intent == null) return null to null

        // Calendar deep-link: singularity://task/{id}
        val data: android.net.Uri? = intent.data
        val deeplinkTaskId: String? = if (data?.scheme == "singularity" && data.host == "task") {
            data.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        } else null

        // Notification tap extra
        val deeplinkViewId: String? = intent.getStringExtra(AndroidNotifier.EXTRA_DEEPLINK_VIEW_ID)

        return deeplinkTaskId to deeplinkViewId
    }

    override fun onResume() {
        super.onResume()
        appUpdateGate.tryOfferUpdateOnMain(this)
    }
}
