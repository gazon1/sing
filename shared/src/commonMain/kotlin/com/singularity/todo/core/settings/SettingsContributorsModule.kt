package com.singularity.todo.core.settings

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
 * Registers the settings contributors that have no module of their own
 * (Notifications, WorkSchedule, Greeting, DefaultAgendaView) as [single] instances for
 * injection via `filterIsInstance<Contributor>()`.
 *
 * Add to [domainModule]: `add(settingsContributorsModule())`.
 */
fun settingsContributorsModule(): Module = module {
    // Appearance is deliberately absent: it is bound by `appearanceSettingsModule()`,
    // which also provides the repository and store its contributor reads from. It used
    // to be bound here as well, and both definitions were added to `domainModule()`, so
    // the second silently replaced the first. Resolution still worked — both constructed
    // the same class from the same dependencies — which is exactly why nothing caught
    // it: `KoinGraphValidationTest` asks "does this resolve?", not "is this defined
    // twice?", and the answer was yes in both cases.
    //
    // The failure mode of leaving it is the reverse of an obvious one. Deleting the
    // binding that happens to win would be a no-op, and deleting the one that is
    // shadowed looks like a cleanup until the other is removed too.
    //
    // Every other contributor below is bound only here, which is why this module exists
    // and why the appearance block was the outlier rather than the rule.

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
