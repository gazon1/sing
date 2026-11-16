package com.singularity.todo.feature.statistics

import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlin.time.Clock

/**
 * Injected dependencies for [StatisticsViewModel].
 *
 * Mirrors the [AgendaDeps] pattern: a data class that groups all repository and
 * platform dependencies so the constructor stays stable as the surface grows.
 */
data class StatisticsDeps(
    val taskRepository: TaskRepository,
    val timeTrackingRepo: TimeTrackingRepository,
    val projectsRepo: ProjectsRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: Clock,
    val settingsRepository: SettingsRepository,
    /**
     * The zone "today" is read in.
     *
     * The day the screen buckets by is the user's day, not the machine's: the
     * agenda reads it the same way, and two surfaces that disagree about what
     * today is would show two different lists of the same tasks.
     */
    val timeZone: TimeZoneProvider,
)
