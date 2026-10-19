package com.singularity.todo.core.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DayOfWeek

/**
 * Contract for work schedule settings.
 */
interface WorkScheduleSettingsRepository {

    val dayStartMinutes: Flow<Int>
    val dayEndMinutes: Flow<Int>
    val lunchStartMinutes: Flow<Int>
    val lunchEndMinutes: Flow<Int>

    /**
     * The days the user works on.
     *
     * A set rather than a pair of booleans about Saturday and Sunday: the
     * section is called "Working Days", and two switches named after the
     * weekend cannot say what a working week is. See [WorkingDays].
     */
    val workingDays: Flow<Set<DayOfWeek>>

    suspend fun setDayStartMinutes(value: Int)
    suspend fun setDayEndMinutes(value: Int)
    suspend fun setLunchStartMinutes(value: Int)
    suspend fun setLunchEndMinutes(value: Int)
    suspend fun setWorkingDays(value: Set<DayOfWeek>)
}

/**
 * Production [WorkScheduleSettingsRepository] backed by DataStore.
 */
class DataStoreWorkScheduleSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    WorkScheduleSettingsRepository {

    private val dayStartPref = intPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "work_day_start_minutes"),
        SettingsDefaults.WorkSchedule.WORK_DAY_START_MINUTES,
    )
    private val dayEndPref = intPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "work_day_end_minutes"),
        SettingsDefaults.WorkSchedule.WORK_DAY_END_MINUTES,
    )
    private val lunchStartPref = intPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "work_lunch_start_minutes"),
        SettingsDefaults.WorkSchedule.WORK_LUNCH_START_MINUTES,
    )
    private val lunchEndPref = intPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "work_lunch_end_minutes"),
        SettingsDefaults.WorkSchedule.WORK_LUNCH_END_MINUTES,
    )

    /**
     * The working days, as stored: the ISO day numbers, comma separated.
     *
     * `null` means the preference has never been written, which is the state a
     * user who installed the app before the working week was a set is in. The
     * two legacy booleans answer for them until they touch the section.
     */
    private val workingDaysPref = nullableStringPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "working_days"),
    )

    /**
     * The two booleans the section used to have. They are read only while
     * [workingDaysPref] has never been written; after that they are inert.
     */
    private val legacySaturdayPref = boolPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "weekend_sat"),
        SettingsDefaults.WorkSchedule.WEEKEND_SAT,
    )
    private val legacySundayPref = boolPref(
        nsKey(SettingsNamespace.WORK_SCHEDULE, "weekend_sun"),
        SettingsDefaults.WorkSchedule.WEEKEND_SUN,
    )

    override val dayStartMinutes: Flow<Int> get() = dayStartPref.flow
    override val dayEndMinutes: Flow<Int> get() = dayEndPref.flow
    override val lunchStartMinutes: Flow<Int> get() = lunchStartPref.flow
    override val lunchEndMinutes: Flow<Int> get() = lunchEndPref.flow

    override val workingDays: Flow<Set<DayOfWeek>> = combine(
        workingDaysPref.flow,
        legacySaturdayPref.flow,
        legacySundayPref.flow,
    ) { stored, saturdayIsWorkingDay, sundayIsWorkingDay ->
        if (stored == null) {
            WorkingDays.fromLegacyFlags(saturdayIsWorkingDay, sundayIsWorkingDay)
        } else {
            WorkingDays.fromStorageString(stored)
        }
    }

    override suspend fun setDayStartMinutes(value: Int) = dayStartPref.set(value)
    override suspend fun setDayEndMinutes(value: Int) = dayEndPref.set(value)
    override suspend fun setLunchStartMinutes(value: Int) = lunchStartPref.set(value)
    override suspend fun setLunchEndMinutes(value: Int) = lunchEndPref.set(value)

    /**
     * Stores the working days as a set.
     *
     * The empty set is stored as an empty string, and an empty string is a
     * value, not an absence: the preference being absent is what says "this
     * user never chose, ask the legacy booleans". A user who turned every day
     * off is a user with no working days, and the two must not collapse into
     * one.
     */
    override suspend fun setWorkingDays(value: Set<DayOfWeek>) =
        workingDaysPref.set(WorkingDays.toStorageString(value))
}
