package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.schedule.GreetingSettingsRepository
import com.singularity.todo.core.schedule.WorkScheduleSettingsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.MergeTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskReopenUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase

/**
 * Dependencies injected into [AgendaViewModel].
 *
 * @param taskRepo Watches tasks for the current user.
 * @param clock The time source for [kotlin.time.Clock.System.now].
 * @param logger For structured logging.
 * @param draftStore User-scoped draft store for pre-filling task creation from section headers.
 * @param reminderScheduler For cancelling reminders when a task is deleted (undo delete).
 * @param currentUser For obtaining the scoped user id (for reminder cancellation).
 * @param taskMutations Bulk operations (delete, complete) for multi-selection.
 * @param workScheduleSettings For reading work-day hours to build work-block sections.
 * @param greetingSettings For reading greeting hour thresholds.
 */
data class AgendaDeps(
    val taskRepo: TaskRepository,
    val clock: kotlin.time.Clock,
    // Required beside the clock (#91). `todayAt` no longer defaults its zone, so a
    // ViewModel that resolves a relative due date has to be told which one; taking
    // it from the host here would put back exactly the machine-dependence the
    // parameter was removed to remove.
    val timeZone: com.singularity.todo.core.platform.TimeZoneProvider,
    val logger: Logger,
    val draftStore: DraftStore,
    val reminderScheduler: ReminderScheduler,
    val currentUser: ProfileAwareCurrentUser,
    val taskMutations: TaskMutationsUseCase,
    /**
     * The rule of closing a task: the write refuses while a prerequisite is
     * open, and a task that comes back to work brings its project with it.
     */
    val completion: TaskReopenUseCase,

    /**
     * Makes two series one series.
     *
     * The user notices it in the agenda: two rows that look like one piece of
     * work, on two different dates, in two different series.
     */
    val mergeSeries: MergeTaskSeriesUseCase,
    val workScheduleSettings: WorkScheduleSettingsRepository,
    val greetingSettings: GreetingSettingsRepository,

    /**
     * The projects, so a row can carry its project's name and colour.
     *
     * Read once per list build through a lookup that **includes closed** projects:
     * a task whose project was closed must keep its badge, and the default lookup
     * filters closed projects out.
     */
    val projectsRepository: ProjectsRepository,
)
