package com.singularity.todo.core.settings

import com.singularity.todo.core.notifications.NotificationsSettingsContributor
import com.singularity.todo.core.notifications.NotificationsSettingsStore
import com.singularity.todo.core.schedule.GreetingSettingsContributor
import com.singularity.todo.core.schedule.GreetingSettingsStore
import com.singularity.todo.core.schedule.WorkScheduleSettingsContributor
import com.singularity.todo.core.schedule.WorkScheduleSettingsStore
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsContributor
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Registers all non-AI settings contributors (Notifications, WorkSchedule, Greeting,
 * DefaultAgendaView) as [single] instances for injection via `getAll<SettingsContributor>()`.
 *
 * Add to [domainModule]: `add(settingsContributorsModule())`.
 */
fun settingsContributorsModule(): Module = module {
    // Notifications
    single { NotificationsSettingsStore(get<SettingsRepository>().notifications) }
    single<SettingsContributor<*, *>> { NotificationsSettingsContributor(get()) }

    // Work Schedule
    single { WorkScheduleSettingsStore(get<SettingsRepository>().workSchedule) }
    single<SettingsContributor<*, *>> { WorkScheduleSettingsContributor(get()) }

    // Greeting
    single { GreetingSettingsStore(get<SettingsRepository>().greeting) }
    single<SettingsContributor<*, *>> { GreetingSettingsContributor(get()) }

    // Default Agenda View
    single { DefaultAgendaViewSettingsStore(get<SettingsRepository>().defaultAgendaView) }
    single<SettingsContributor<*, *>> { DefaultAgendaViewSettingsContributor(get()) }
}
