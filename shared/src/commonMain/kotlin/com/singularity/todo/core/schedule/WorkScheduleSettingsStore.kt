package com.singularity.todo.core.schedule

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes work schedule settings.
 *
 * Contributes [SettingsSection.WorkSchedule] to the unified settings UI.
 * Registration: `single<SettingsContributor> { WorkScheduleSettingsContributor(get()) }`.
 */
class WorkScheduleSettingsStore(
    private val workSchedule: WorkScheduleSettingsRepository,
) {
    /**
     * Work schedule section — all 6 fields from [WorkScheduleSettingsRepository].
     * Chained combine() calls avoid type-inference issues with 6-flow overloads.
     */
    fun observe(): Flow<SettingsSection.WorkSchedule> = combine(
        combine(workSchedule.dayStartMinutes, workSchedule.dayEndMinutes) { dayStart, dayEnd -> dayStart to dayEnd },
        combine(workSchedule.lunchStartMinutes, workSchedule.lunchEndMinutes) { lunchStart, lunchEnd -> lunchStart to lunchEnd },
        combine(workSchedule.weekendSat, workSchedule.weekendSun) { sat, sun -> sat to sun },
    ) { (dayStart, dayEnd), (lunchStart, lunchEnd), (weekendSat, weekendSun) ->
        SettingsSection.WorkSchedule(
            dayStartMinutes = dayStart,
            dayEndMinutes = dayEnd,
            lunchStartMinutes = lunchStart,
            lunchEndMinutes = lunchEnd,
            weekendSat = weekendSat,
            weekendSun = weekendSun,
        )
    }

    suspend fun process(intent: SettingsIntent.WorkSchedule) {
        when (intent) {
            is SettingsIntent.WorkSchedule.UpdateWorkDayStart -> workSchedule.setDayStartMinutes(intent.minutes)
            is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> workSchedule.setDayEndMinutes(intent.minutes)
            is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> workSchedule.setLunchStartMinutes(intent.minutes)
            is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> workSchedule.setLunchEndMinutes(intent.minutes)
            is SettingsIntent.WorkSchedule.UpdateWeekendSat -> workSchedule.setWeekendSat(intent.value)
            is SettingsIntent.WorkSchedule.UpdateWeekendSun -> workSchedule.setWeekendSun(intent.value)
        }
    }
}
