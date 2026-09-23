package com.singularity.todo.feature.calendar_sync.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Android implementation of [CalendarProviderPort] using ContentResolver.
 *
 * Requires android.permission.READ_CALENDAR and android.permission.WRITE_CALENDAR.
 * Uses CalendarContract.Events as the URI target.
 *
 * @param context Android context (typically ApplicationContext).
 * @param accountNameProvider A provider that returns the current account name (userId from
 *                    ProfileAwareCurrentUser). Called on every operation so that profile
 *                    switches are reflected without recreating the provider.
 * @param syncRepo Repository used to resolve the current target app package on each operation.
 *                    Passed directly so this class doesn't need to depend on a platform-specific
 *                    provider type; it reads the Flow at call time.
 */
class AndroidCalendarProvider(
    private val context: Context,
    private val accountNameProvider: () -> String,
    private val syncRepo: CalendarSyncRepository,
) : CalendarProviderPort {

    private val contentResolver: ContentResolver
        get() = context.contentResolver

    /** Lazily resolves the target app package from settings. null = use system default. */
    private suspend fun resolveAppPackage(): String? =
        syncRepo.observeTargetAppPackage().firstOrNull()

    override suspend fun getAvailableCalendars(): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val projection = arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                )
                val uri = CalendarContract.Calendars.CONTENT_URI
                val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
                val calendars = mutableMapOf<String, String>()
                contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx)
                        val name = cursor.getString(nameIdx) ?: id
                        calendars[id] = name
                    }
                }
                calendars
            }
        }

    override suspend fun insertEvent(event: CalendarSyncEvent): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appPkg = resolveAppPackage()
                val values = toContentValues(event, accountNameProvider(), appPkg)
                val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: throw IllegalStateException("Insert returned null URI")
                val eventId = ContentUris.parseId(uri)
                eventId
            }
        }

    override suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appPkg = resolveAppPackage()
                val values = toContentValues(event, accountNameProvider(), appPkg)
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val rows = contentResolver.update(uri, values, null, null)
                if (rows == 0) {
                    throw IllegalStateException("Update affected 0 rows for eventId=$eventId")
                }
                eventId
            }
        }

    override suspend fun deleteEvent(eventId: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val rows = contentResolver.delete(uri, null, null)
                if (rows == 0) {
                    throw IllegalStateException("Delete affected 0 rows for eventId=$eventId")
                }
            }
        }

    override suspend fun queryEvents(
        calendarId: String?,
        fromMs: Long,
        toMs: Long,
    ): Result<Map<String, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DESCRIPTION,
            )
            val selection = buildString {
                append("(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?)")
                if (calendarId != null) {
                    append(" AND ${CalendarContract.Events.CALENDAR_ID} = ?")
                }
                append(" AND ${CalendarContract.Events.ACCOUNT_NAME} = ?")
            }
            val args = if (calendarId != null) {
                arrayOf(fromMs.toString(), toMs.toString(), calendarId, accountNameProvider())
            } else {
                arrayOf(fromMs.toString(), toMs.toString(), accountNameProvider())
            }

            val result = mutableMapOf<String, Long>()
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(idIdx)
                    val desc = cursor.getString(descIdx) ?: ""
                    val taskId = extractTaskId(desc)
                    if (taskId != null) {
                        result[taskId] = eventId
                    }
                }
            }
            result
        }
    }

    private fun extractTaskId(description: String): String? {
        val marker = "${CalendarSyncEvent.DEEP_LINK_SCHEME}://${CalendarSyncEvent.DEEP_LINK_HOST}/"
        val idx = description.indexOf(marker)
        if (idx < 0) return null
        return description.substring(idx + marker.length).takeWhile { it.isLetterOrDigit() || it == '-' }
    }

    private fun toContentValues(
        syncEvent: CalendarSyncEvent,
        accountName: String,
        appPackage: String?,
    ): ContentValues {
        val tz = java.util.TimeZone.getDefault().id
        val cv = ContentValues()
        cv.put(CalendarContract.Events.CALENDAR_ID, syncEvent.calendarId.toLongOrNull() ?: 1L)
        cv.put(CalendarContract.Events.TITLE, syncEvent.title)
        cv.put(CalendarContract.Events.DESCRIPTION, syncEvent.description)
        cv.put(CalendarContract.Events.DTSTART, syncEvent.startMs)
        cv.put(CalendarContract.Events.DTEND, if (syncEvent.allDay) syncEvent.startMs else syncEvent.endMs)
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, tz)
        cv.put(CalendarContract.Events.ALL_DAY, if (syncEvent.allDay) 1 else 0)
        if (syncEvent.rrule != null) {
            cv.put(CalendarContract.Events.RRULE, syncEvent.rrule)
        }
        if (syncEvent.color != null) {
            cv.put(CalendarContract.Events.EVENT_COLOR, syncEvent.color.toInt())
        }
        cv.put(CalendarContract.Events.ACCOUNT_NAME, accountName)
        cv.put(CalendarContract.Events.ACCOUNT_TYPE, "com.singularity.todo")
        // When appPackage is set, tag the event so it can be scoped to that app on queries
        if (appPackage != null) {
            cv.put(CalendarContract.Events.CALENDAR_DISPLAY_NAME, appPackage)
        }
        return cv
    }
}
