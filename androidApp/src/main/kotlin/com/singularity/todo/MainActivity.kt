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

        val resolved = resolveDeepLinkIntents(intent)

        setContent {
            App(
                deeplinkViewId = resolved.deeplinkViewId,
                deeplinkTaskId = resolved.deeplinkTaskId,
                deeplinkMagicEmail = resolved.deeplinkMagicEmail,
                deeplinkMagicToken = resolved.deeplinkMagicToken,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val resolved = resolveDeepLinkIntents(intent)
        // Split into two named checks rather than one four-term condition: `ComplexCondition`
        // reads a single long `||` chain as a smell, and this one was not one — it is
        // "did the intent resolve to anything", which is one question with four answers.
        val hasDeepLink = resolved.deeplinkTaskId != null || resolved.deeplinkViewId != null
        val hasMagicLink = resolved.deeplinkMagicEmail != null || resolved.deeplinkMagicToken != null
        if (hasDeepLink || hasMagicLink) {
            setContent {
                App(
                    deeplinkViewId = resolved.deeplinkViewId,
                    deeplinkTaskId = resolved.deeplinkTaskId,
                    deeplinkMagicEmail = resolved.deeplinkMagicEmail,
                    deeplinkMagicToken = resolved.deeplinkMagicToken,
                )
            }
        }
    }

    /**
     * Deep-link intent resolution result, combining three independent sources:
     * 1. `intent.data` — `singularity://task/{id}` from calendar event taps → deeplinkTaskId
     * 2. `AndroidNotifier.EXTRA_DEEPLINK_VIEW_ID` — from notification taps → deeplinkViewId
     * 3. `intent.data` — `singularity://verify?email=&token=` from magic link emails → deeplinkMagicEmail / deeplinkMagicToken
     *
     * These are independent, so all can be non-null if multiple intent extras are somehow set.
     */
    private data class ResolvedDeepLinks(
        val deeplinkTaskId: String?,
        val deeplinkViewId: String?,
        val deeplinkMagicEmail: String?,
        val deeplinkMagicToken: String?,
    )

    private fun resolveDeepLinkIntents(intent: Intent?): ResolvedDeepLinks {
        if (intent == null) return ResolvedDeepLinks(null, null, null, null)

        val data: android.net.Uri? = intent.data

        // Calendar deep-link: singularity://task/{id}
        val deeplinkTaskId: String? = if (data?.scheme == "singularity" && data.host == "task") {
            data.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        // Magic link deep-link: singularity://verify?email=&token=
        val deeplinkMagicEmail: String? = data?.getQueryParameter("email")?.takeIf { it.isNotBlank() }
        val deeplinkMagicToken: String? = data?.getQueryParameter("token")?.takeIf { it.isNotBlank() }

        // Notification tap extra (independent of intent.data)
        val deeplinkViewId: String? = intent.getStringExtra(AndroidNotifier.EXTRA_DEEPLINK_VIEW_ID)

        return ResolvedDeepLinks(
            deeplinkTaskId = deeplinkTaskId,
            deeplinkViewId = deeplinkViewId,
            deeplinkMagicEmail = deeplinkMagicEmail,
            deeplinkMagicToken = deeplinkMagicToken,
        )
    }

    override fun onResume() {
        super.onResume()
        appUpdateGate.tryOfferUpdateOnMain(this)
    }
}
