package com.singularity.todo.feature.calendar_sync.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [CalendarAppQueries] using [PackageManager].
 *
 * Finds apps that can handle `ACTION_INSERT` on `CalendarContract.Events.CONTENT_URI`,
 * which is the canonical intent for creating calendar events.
 */
class AndroidCalendarAppQueries(
    private val context: Context,
) : CalendarAppQueries {

    override suspend fun listInstalled(): List<CalendarAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // Try ACTION_INSERT first — most calendar apps register for it
        val insertApps = resolveApps(pm, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI))
        // Also try ACTION_EDIT — some apps only advertise EDIT
        val editApps = resolveApps(pm, Intent(Intent.ACTION_EDIT).setData(CalendarContract.Events.CONTENT_URI))

        // Union, sorted by display name, deduplicated by package name
        (insertApps + editApps)
            .associateBy { it.packageName }
            .values
            .sortedBy { it.displayName.lowercase() }
    }

    private fun resolveApps(pm: PackageManager, intent: Intent): List<CalendarAppInfo> {
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val packageName = appInfo.packageName
                // Skip our own app
                if (packageName == context.packageName) return@mapNotNull null
                val displayName = pm.getApplicationLabel(appInfo).toString()
                    .takeIf { it.isNotBlank() }
                    ?: packageName
                CalendarAppInfo(packageName, displayName)
            }
    }
}
