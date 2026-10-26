package com.singularity.todo.feature.plans

import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.plans.domain.port.PlansSettingsRepository
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansDeps
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * DI module for the Plans feature.
 *
 * The granularity is read through the settings bundle rather than bound twice:
 * [SettingsRepository] already owns the feature's namespace, and a second
 * binding of the same repository would be a second place where the choice
 * lives.
 */
fun plansModule(): Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<PlansSettingsRepository> { get<SettingsRepository>().plans }

    // ─── ViewModel ──────────────────────────────────────────────────────

    viewModel {
        PlansViewModel(
            deps = PlansDeps(
                taskRepo = get<TaskRepository>(),
                settings = get<PlansSettingsRepository>(),
                clock = get<Clock>(),
                timeZone = get<TimeZoneProvider>(),
                reminderScheduler = get<ReminderScheduler>(),
                currentUser = get<ProfileAwareCurrentUser>(),
            ),
            crashReporter = get(),
        )
    }
}
