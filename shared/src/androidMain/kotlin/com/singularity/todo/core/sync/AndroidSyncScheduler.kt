package com.singularity.todo.core.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import co.touchlab.kermit.Logger
import kotlin.time.Duration

/**
 * Android [SyncScheduler] backed by [AlarmManager].
 *
 * Uses `setInexactRepeating()` — Android will batch alarms to save battery.
 * Sync fires via a broadcast that triggers [SyncRunner] from the app's [android.app.Application].
 *
 * The [PendingIntent] is a no-op receiver; the real sync is triggered via
 * [SyncRepository.syncOnce] called from the broadcast receiver registered in [AndroidShell].
 */
class AndroidSyncScheduler(
    private val context: Context,
) : SyncScheduler {

    private val log = Logger.withTag("AndroidSyncScheduler")

    companion object {
        private const val REQUEST_CODE = 1001
        private val INTENT_ACTION = "com.singularity.todo.SYNC_ALARM"
    }

    private var pendingIntent: PendingIntent? = null

    override fun schedule(interval: Duration) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
            action = INTENT_ACTION
        }

        pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val intervalMillis = interval.inWholeMilliseconds
        // Use setInexactRepeating for battery optimization — Android batches inexact alarms.
        // First trigger is immediate, then repeats at interval.
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis(), // first fire immediately
            intervalMillis,
            pendingIntent,
        )
        log.d { "Scheduled sync (interval=$interval)" }
    }

    override fun cancel() {
        pendingIntent?.let { pi ->
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pi)
            pi.cancel()
        }
        pendingIntent = null
        log.d { "Sync alarm cancelled" }
    }
}
