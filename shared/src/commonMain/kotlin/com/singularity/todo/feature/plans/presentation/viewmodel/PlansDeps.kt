package com.singularity.todo.feature.plans.presentation.viewmodel

import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.plans.domain.port.PlansSettingsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * The ports the plan screen needs.
 *
 * @param taskRepo The tasks. The whole list, not the horizon's slice: a
 *   prerequisite outside the loaded set does not block a task, so a narrowed
 *   list would show a task as unblocked that the agenda shows as blocked.
 * @param settings Where the chosen granularity lives.
 * @param clock The clock the screen reads the date from — injected, so a test
 *   can assert what "today" is.
 * @param timeZone The user's own zone. `todayAt` no longer defaults it, and a
 *   screen that resolved its dates in the host's zone would be wrong for every
 *   user east or west of it.
 * @param reminderScheduler Cancels the reminders of a task that is swiped away.
 * @param currentUser The identity a reminder is cancelled for.
 */
data class PlansDeps(
    val taskRepo: TaskRepository,
    val settings: PlansSettingsRepository,
    val clock: kotlin.time.Clock,
    val timeZone: TimeZoneProvider,
    val reminderScheduler: ReminderScheduler,
    val currentUser: ProfileAwareCurrentUser,
)
