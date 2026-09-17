package com.singularity.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.singularity.todo.core.notifications.ReminderBroadcastReceiver

/**
 * Main (and only) Activity.
 *
 * Koin is started once in [SingularityApp.onCreate] — process-scoped,
 * guaranteed single initialization before any Activity runs.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read the deeplink viewId from notification tap.
        // Null when launching normally or from an intent without this extra.
        val deeplinkViewId: String? = intent.getStringExtra(ReminderBroadcastReceiver.EXTRA_DEEPLINK_VIEW_ID)

        setContent {
            App(deeplinkViewId = deeplinkViewId)
        }
    }
}
