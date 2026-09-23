package com.singularity.todo.core.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for greeting hour settings.
 */
interface GreetingSettingsRepository {

    val morningEndHour: Flow<Int>
    val afternoonEndHour: Flow<Int>

    suspend fun setMorningEndHour(hour: Int)
    suspend fun setAfternoonEndHour(hour: Int)
}

/**
 * Production [GreetingSettingsRepository] backed by DataStore.
 */
class DataStoreGreetingSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : GreetingSettingsRepository {

    companion object {
        val MORNING_END_HOUR = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.GREETING, "morning_end_hour"))
        val AFTERNOON_END_HOUR = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.GREETING, "afternoon_end_hour"))
    }

    override val morningEndHour: Flow<Int> = dataStore.data.map {
        it[MORNING_END_HOUR] ?: SettingsDefaults.Greeting.MORNING_END_HOUR
    }

    override val afternoonEndHour: Flow<Int> = dataStore.data.map {
        it[AFTERNOON_END_HOUR] ?: SettingsDefaults.Greeting.AFTERNOON_END_HOUR
    }

    override suspend fun setMorningEndHour(hour: Int) {
        dataStore.edit { it[MORNING_END_HOUR] = hour.coerceIn(0, 23) }
    }

    override suspend fun setAfternoonEndHour(hour: Int) {
        dataStore.edit { it[AFTERNOON_END_HOUR] = hour.coerceIn(0, 23) }
    }
}
