package com.singularity.todo.feature.calendar_sync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [GoogleCalendarSettingsRepository].
 *
 * Shares the `calendar_sync` DataStore file with [CalendarSyncSettingsRepositoryImpl] rather
 * than declaring a second one. They are the same preference store reached through a
 * different key namespace, and a second file would mean a second writer, a second
 * corruption mode, and a second thing to migrate.
 *
 * Keys are prefixed `google_cal_` so a Google setting can never collide with a system
 * calendar one. `calendar_sync_target_id` and `google_cal_calendar_id` are both "the
 * calendar to write to" and they mean different things; sharing a name would have been
 * shorter and wrong.
 */
class GoogleCalendarSettingsRepositoryImpl(private val dataStore: DataStore<Preferences>) :
    GoogleCalendarSettingsRepository {

    override fun observeSelectedCalendarId(): Flow<String?> = dataStore.data
        .catchDataStoreIoError()
        .map { it[GOOGLE_CALENDAR_ID] }

    override suspend fun setSelectedCalendarId(calendarId: String?) {
        dataStore.edit { prefs ->
            if (calendarId != null) {
                prefs[GOOGLE_CALENDAR_ID] = calendarId
            } else {
                prefs.remove(GOOGLE_CALENDAR_ID)
            }
        }
    }

    override fun observeImportForeignEvents(): Flow<Boolean> = dataStore.data
        .catchDataStoreIoError()
        // Default true: connecting a Google account is usually done *because* it already
        // holds the events the user wants to see. An absent key means "never chosen", which
        // is not the same as "chosen no" — and defaulting to false would make a first-run
        // sync look broken when it silently imported nothing.
        .map { it[GOOGLE_IMPORT_FOREIGN] ?: true }

    override suspend fun setImportForeignEvents(enabled: Boolean) {
        dataStore.edit { it[GOOGLE_IMPORT_FOREIGN] = enabled }
    }

    private companion object {
        val GOOGLE_CALENDAR_ID = stringPreferencesKey("google_cal_calendar_id")
        val GOOGLE_IMPORT_FOREIGN = booleanPreferencesKey("google_cal_import_foreign")
    }
}
