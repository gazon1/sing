package com.singularity.todo.core.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow

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
 * Hour values are coerced to 0–23 on write.
 */
class DataStoreGreetingSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    GreetingSettingsRepository {

    private val morningEndPref = intPref(
        nsKey(SettingsNamespace.GREETING, "morning_end_hour"),
        SettingsDefaults.Greeting.MORNING_END_HOUR,
        range = 0..23,
    )
    private val afternoonEndPref = intPref(
        nsKey(SettingsNamespace.GREETING, "afternoon_end_hour"),
        SettingsDefaults.Greeting.AFTERNOON_END_HOUR,
        range = 0..23,
    )

    override val morningEndHour: Flow<Int> get() = morningEndPref.flow
    override val afternoonEndHour: Flow<Int> get() = afternoonEndPref.flow

    override suspend fun setMorningEndHour(hour: Int) = morningEndPref.set(hour)
    override suspend fun setAfternoonEndHour(hour: Int) = afternoonEndPref.set(hour)
}
