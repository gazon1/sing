package com.singularity.todo.core.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for work schedule settings.
 */
interface WorkScheduleSettingsRepository {

    val dayStartMinutes: Flow<Int>
    val dayEndMinutes: Flow<Int>
    val lunchStartMinutes: Flow<Int>
    val lunchEndMinutes: Flow<Int>
    val weekendSat: Flow<Boolean>
    val weekendSun: Flow<Boolean>

    suspend fun setDayStartMinutes(value: Int)
    suspend fun setDayEndMinutes(value: Int)
    suspend fun setLunchStartMinutes(value: Int)
    suspend fun setLunchEndMinutes(value: Int)
    suspend fun setWeekendSat(value: Boolean)
    suspend fun setWeekendSun(value: Boolean)
}

/**
 * Production [WorkScheduleSettingsRepository] backed by DataStore.
 */
class DataStoreWorkScheduleSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : WorkScheduleSettingsRepository {

    companion object {
        val DAY_START_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_day_start_minutes"))
        val DAY_END_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_day_end_minutes"))
        val LUNCH_START_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_lunch_start_minutes"))
        val LUNCH_END_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_lunch_end_minutes"))
        val WEEKEND_SAT = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "weekend_sat"))
        val WEEKEND_SUN = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "weekend_sun"))
    }

    override val dayStartMinutes: Flow<Int> = dataStore.data.map {
        it[DAY_START_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_DAY_START_MINUTES
    }
    override val dayEndMinutes: Flow<Int> = dataStore.data.map {
        it[DAY_END_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_DAY_END_MINUTES
    }
    override val lunchStartMinutes: Flow<Int> = dataStore.data.map {
        it[LUNCH_START_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_LUNCH_START_MINUTES
    }
    override val lunchEndMinutes: Flow<Int> = dataStore.data.map {
        it[LUNCH_END_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_LUNCH_END_MINUTES
    }
    override val weekendSat: Flow<Boolean> = dataStore.data.map {
        it[WEEKEND_SAT] ?: SettingsDefaults.WorkSchedule.WEEKEND_SAT
    }
    override val weekendSun: Flow<Boolean> = dataStore.data.map {
        it[WEEKEND_SUN] ?: SettingsDefaults.WorkSchedule.WEEKEND_SUN
    }

    override suspend fun setDayStartMinutes(value: Int) {
        dataStore.edit { it[DAY_START_MINUTES] = value }
    }

    override suspend fun setDayEndMinutes(value: Int) {
        dataStore.edit { it[DAY_END_MINUTES] = value }
    }

    override suspend fun setLunchStartMinutes(value: Int) {
        dataStore.edit { it[LUNCH_START_MINUTES] = value }
    }

    override suspend fun setLunchEndMinutes(value: Int) {
        dataStore.edit { it[LUNCH_END_MINUTES] = value }
    }

    override suspend fun setWeekendSat(value: Boolean) {
        dataStore.edit { it[WEEKEND_SAT] = value }
    }

    override suspend fun setWeekendSun(value: Boolean) {
        dataStore.edit { it[WEEKEND_SUN] = value }
    }
}
