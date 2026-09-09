package com.singularity.todo.core.di

import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.tasks.TaskDetailDeps
import com.singularity.todo.feature.tasks.TaskDetailViewModel
import com.singularity.todo.feature.tasks.TaskEditorViewModel
import com.singularity.todo.feature.tasks.TaskEditorDeps
import com.singularity.todo.feature.tasks.AttachmentSaver
import com.singularity.todo.feature.tasks.AttachmentsViewModelAttachmentSaver
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.tasks.TasksByProjectViewModel
import com.singularity.todo.feature.archive.ArchiveRepository
import com.singularity.todo.feature.archive.ArchiveViewModel
import com.singularity.todo.feature.archive.TaskDaoArchiveRepository
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.checklist.ChecklistEditorViewModel
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.checklist.RoomChecklistRepository
import com.singularity.todo.feature.pomodoro.PomodoroRepository
import com.singularity.todo.feature.pomodoro.InMemoryPomodoroRepository
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.statistics.StatisticsViewModel
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.SearchViewModel
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.clock.DelayAutosaveScheduler
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Tasks feature DI: repositories, use cases, ViewModels.
 * Does NOT include AI tools — those live in [aiToolsCoreModule].
 */
fun tasksModule(): org.koin.core.module.Module = module {
    // ─── Repositories ─────────────────────────────────────────────────────

    single<TaskRepository> { TaskRepositoryImpl(get(), get()) }

    single<ArchiveRepository> { TaskDaoArchiveRepository(get(), get()) }

    single<ChecklistRepository> { RoomChecklistRepository(get(), get()) }

    factory<PomodoroRepository> { InMemoryPomodoroRepository() }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateTaskUseCase(get(), get()) }
    factory { UpdateTaskUseCase(get(), get()) }
    factory { TaskMutationsUseCase(get()) }

    factory { ChecklistUseCase(get(), get()) }

    factory { SearchUseCase(get(), get(), get(), get()) }

    factory { PomodoroTimer(get(), get(), get()) }

    // ─── Autosave ───────────────────────────────────────────────────────

    single<AutosaveScheduler> { DelayAutosaveScheduler() }

    // ─── Ports ──────────────────────────────────────────────────────────

    factory<IdGenerator> { UlidIdGenerator }

    single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

    single<AttachmentSaver> { AttachmentsViewModelAttachmentSaver { get<AttachmentsViewModel>() } }

    // ─── Reminders ──────────────────────────────────────────────────────

    factory { ReminderScheduler(co.touchlab.kermit.Logger.withTag("ReminderScheduler"), get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    // TasksViewModel with AI deps: registered in aiToolsCoreModule (has nullable AI use cases)
    // TasksViewModel without AI deps: not needed — AI-less version uses same class, handles null gracefully

    viewModel {
        TaskDetailViewModel(
            deps = TaskDetailDeps(
                taskRepo = get(),
                updateTask = get(),
                createTask = get(),
                projectsRepo = get(),
                tagsRepo = get(),
                checklistUseCase = get(),
                reminderRepo = get(),
                attachmentsRepo = get(),
                currentUser = get(),
                timeZoneProvider = get(),
            )
        )
    }

    // TaskEditorViewModel — runtime parameter (initialDueDate)
    viewModel { (initialDueDate: kotlinx.datetime.LocalDate?) ->
        TaskEditorViewModel(
            deps = TaskEditorDeps(
                createTask = get(),
                updateTask = get(),
                clock = get(),
                currentUser = get(),
                taskRepository = get(),
                checklistUseCase = get(),
                reminderRepository = get(),
                attachmentSaver = get(),
                idGen = get(),
                timeZoneProvider = get(),
            ),
            initialDueDate = initialDueDate,
        )
    }

    viewModelOf(::ChecklistEditorViewModel)

    viewModelOf(::ArchiveViewModel)

    viewModelOf(::StatisticsViewModel)

    viewModelOf(::SearchViewModel)

    viewModel { (projectId: com.singularity.todo.feature.projects.ProjectId) ->
        TasksByProjectViewModel(
            projectId = projectId,
            taskRepo = get(),
            projectRepo = get(),
            createTask = get(),
            updateTask = get(),
            currentUser = get(),
        )
    }
}
