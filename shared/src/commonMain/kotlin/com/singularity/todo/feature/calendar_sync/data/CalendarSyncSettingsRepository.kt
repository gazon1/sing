package com.singularity.todo.feature.calendar_sync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Emits [emptyPreferences] when [IOException] is thrown (e.g. corrupted DataStore file),
 * re-throwing all other exceptions.
 */
private fun Flow<Preferences>.catchIOExceptionEmitEmpty(): Flow<Preferences> =
    catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

/**
 * DataStore-backed implementation of [CalendarSyncRepository].
 *
 * Stores:
 * - `calendar_sync_enabled` (bool) — whether sync is turned on
 * - `calendar_sync_target_id` (string) — Android calendar ID to sync into
 * - `calendar_sync_app_pkg` (string) — target calendar app package (nullable; null = default)
 * - `calendar_sync_last_at` (long) — epoch millis of last successful sync
 * - `calendar_sync_status` (string) — serialized [CalendarSyncStatus]
 */
class CalendarSyncSettingsRepository(private val dataStore: DataStore<Preferences>) : CalendarSyncRepository {

    companion object {
        val CALENDAR_SYNC_ENABLED = booleanPreferencesKey("calendar_sync_enabled")
        val CALENDAR_SYNC_TARGET_ID = stringPreferencesKey("calendar_sync_target_id")
        val CALENDAR_SYNC_APP_PKG = stringPreferencesKey("calendar_sync_app_pkg")
        val CALENDAR_SYNC_LAST_AT = longPreferencesKey("calendar_sync_last_at")
        val CALENDAR_SYNC_STATUS = stringPreferencesKey("calendar_sync_status")
    }

    override fun observeEnabled(): Flow<Boolean> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[CALENDAR_SYNC_ENABLED] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[CALENDAR_SYNC_ENABLED] = enabled }
    }

    override fun observeTargetCalendarId(): Flow<String?> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[CALENDAR_SYNC_TARGET_ID] }

    override suspend fun setTargetCalendarId(calendarId: String) {
        dataStore.edit { it[CALENDAR_SYNC_TARGET_ID] = calendarId }
    }

    override fun observeTargetAppPackage(): Flow<String?> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[CALENDAR_SYNC_APP_PKG] }

    override suspend fun setTargetAppPackage(packageName: String?) {
        dataStore.edit { prefs ->
            if (packageName != null) {
                prefs[CALENDAR_SYNC_APP_PKG] = packageName
            } else {
                prefs.remove(CALENDAR_SYNC_APP_PKG)
            }
        }
    }

    override fun observeLastSyncedAt(): Flow<Long?> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[CALENDAR_SYNC_LAST_AT] }

    override suspend fun setLastSyncedAt(ts: Long) {
        dataStore.edit { it[CALENDAR_SYNC_LAST_AT] = ts }
    }

    override fun observeStatus(): Flow<CalendarSyncStatus> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { prefs ->
            val statusName = prefs[CALENDAR_SYNC_STATUS]
            when (statusName) {
                "Disabled" -> CalendarSyncStatus.Disabled

                "Syncing" -> CalendarSyncStatus.Syncing

                else -> {
                    val reason = statusName?.removePrefix("Failed:")
                    if (reason != null) {
                        CalendarSyncStatus.Failed(reason)
                    } else {
                        val lastAt = prefs[CALENDAR_SYNC_LAST_AT]
                        CalendarSyncStatus.Idle(lastAt)
                    }
                }
            }
        }

    override suspend fun setStatus(status: CalendarSyncStatus) {
        dataStore.edit { prefs ->
            when (status) {
                is CalendarSyncStatus.Disabled -> {
                    prefs[CALENDAR_SYNC_STATUS] = "Disabled"
                }

                is CalendarSyncStatus.Idle -> {
                    prefs[CALENDAR_SYNC_STATUS] = "Idle"
                    status.lastSyncedAt?.let { prefs[CALENDAR_SYNC_LAST_AT] = it }
                }

                is CalendarSyncStatus.Syncing -> {
                    prefs[CALENDAR_SYNC_STATUS] = "Syncing"
                }

                is CalendarSyncStatus.Failed -> {
                    prefs[CALENDAR_SYNC_STATUS] = "Failed:${status.reason}"
                }
            }
        }
    }
}
