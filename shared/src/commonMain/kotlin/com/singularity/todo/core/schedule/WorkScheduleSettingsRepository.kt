package com.singularity.todo.core.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow

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
    dataStore: DataStore<Preferences>,
) : BaseSettingsRepository(dataStore), WorkScheduleSettingsRepository {

    private val dayStartPref = intPref(nsKey(SettingsNamespace.WORK_SCHEDULE, "work_day_start_minutes"), SettingsDefaults.WorkSchedule.WORK_DAY_START_MINUTES)
    private val dayEndPref = intPref(nsKey(SettingsNamespace.WORK_SCHEDULE, "work_day_end_minutes"), SettingsDefaults.WorkSchedule.WORK_DAY_END_MINUTES)
    private val lunchStartPref = intPref(nsKey(SettingsNamespace.WORK_SCHEDULE, "work_lunch_start_minutes"), SettingsDefaults.WorkSchedule.WORK_LUNCH_START_MINUTES)
    private val lunchEndPref = intPref(nsKey(SettingsNamespace.WORK_SCHEDULE, "work_lunch_end_minutes"), SettingsDefaults.WorkSchedule.WORK_LUNCH_END_MINUTES)
    private val weekendSatPref = boolPref(nsKey(SettingsNamespace.WORK_SCHEDULE, "weekend_sat"), SettingsDefaults.WorkSchedule.WEEKEND_SAT)
    private val weekendSunPref = boolPref(nsKey(SettingsNamespace.WORK_SCHEDULE, "weekend_sun"), SettingsDefaults.WorkSchedule.WEEKEND_SUN)

    override val dayStartMinutes: Flow<Int> get() = dayStartPref.flow
    override val dayEndMinutes: Flow<Int> get() = dayEndPref.flow
    override val lunchStartMinutes: Flow<Int> get() = lunchStartPref.flow
    override val lunchEndMinutes: Flow<Int> get() = lunchEndPref.flow
    override val weekendSat: Flow<Boolean> get() = weekendSatPref.flow
    override val weekendSun: Flow<Boolean> get() = weekendSunPref.flow

    override suspend fun setDayStartMinutes(value: Int) = dayStartPref.set(value)
    override suspend fun setDayEndMinutes(value: Int) = dayEndPref.set(value)
    override suspend fun setLunchStartMinutes(value: Int) = lunchStartPref.set(value)
    override suspend fun setLunchEndMinutes(value: Int) = lunchEndPref.set(value)
    override suspend fun setWeekendSat(value: Boolean) = weekendSatPref.set(value)
    override suspend fun setWeekendSun(value: Boolean) = weekendSunPref.set(value)
}
