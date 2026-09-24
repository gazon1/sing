package com.singularity.todo.core.settings

import com.singularity.todo.core.appearance.AppearanceContributor
import com.singularity.todo.core.appearance.AppearanceSettingsContributor
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.notifications.NotificationsSettingsContributor
import com.singularity.todo.core.notifications.NotificationsSettingsStore
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.GreetingSettingsContributor
import com.singularity.todo.core.schedule.GreetingSettingsStore
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.schedule.WorkScheduleSettingsContributor
import com.singularity.todo.core.schedule.WorkScheduleSettingsStore
import com.singularity.todo.feature.agenda.DefaultAgendaViewContributor
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsContributor
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Registers all settings contributors (Appearance, Notifications, WorkSchedule, Greeting,
 * DefaultAgendaView) as [single] instances for injection via `filterIsInstance<Contributor>()`.
 *
 * Add to [domainModule]: `add(settingsContributorsModule())`.
 */
fun settingsContributorsModule(): Module = module {
    // Appearance — AppearanceSettingsRepository is provided by appearanceSettingsModule()
    single<AppearanceContributor> { AppearanceSettingsContributor(get()) }

    // Notifications
    single { NotificationsSettingsStore(get<SettingsRepository>().notifications) }
    single<NotificationsContributor> { NotificationsSettingsContributor(get()) }

    // Work Schedule
    single { WorkScheduleSettingsStore(get<SettingsRepository>().workSchedule) }
    single<WorkScheduleContributor> { WorkScheduleSettingsContributor(get()) }

    // Greeting
    single { GreetingSettingsStore(get<SettingsRepository>().greeting) }
    single<GreetingContributor> { GreetingSettingsContributor(get()) }

    // Default Agenda View
    single { DefaultAgendaViewSettingsStore(get<SettingsRepository>().defaultAgendaView) }
    single<DefaultAgendaViewContributor> { DefaultAgendaViewSettingsContributor(get()) }
}
