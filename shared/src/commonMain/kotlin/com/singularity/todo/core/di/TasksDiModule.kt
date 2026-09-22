package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.archive.ArchiveViewModel
import com.singularity.todo.feature.archive.TaskDaoArchiveRepository
import com.singularity.todo.feature.checklist.ChecklistEditorViewModel
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.checklist.RoomChecklistRepository
import com.singularity.todo.feature.pomodoro.PomodoroRepository
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.SearchViewModel
import com.singularity.todo.feature.statistics.StatisticsViewModel
import com.singularity.todo.feature.tasks.data.AttachmentSaverImpl
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.model.AttachmentSaver
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf

/**
 * Tasks feature DI: repositories, use cases, ViewModels.
 * Does NOT include AI tools — those live in [aiToolsModule].
 *
 * Navigation entries (navigation{}) live in platform-specific sources:
 * - androidMain: TasksNavEntries.kt (Nav3)
 * - jvmMain: TasksNavEntries.kt (Nav3)
 * Both delegate to the ViewModels and Screens defined here.
 */
fun tasksModule(): org.koin.core.module.Module = module {
    // ─── Repositories ─────────────────────────────────────────────────────

    single<TaskRepository> { TaskRepositoryImpl(get(), get(), get()) }

    singleOf(::TaskDaoArchiveRepository)

    single<ChecklistRepository> { RoomChecklistRepository(get(), get()) }

    factoryOf(::PomodoroRepository)

    // ─── Use Cases ──────────────────────────────────────────────────────

    factoryOf(::CreateTaskUseCase)
    factoryOf(::CreateTaskFromDraftUseCase)
    factoryOf(::UpdateTaskUseCase)
    factoryOf(::TaskMutationsUseCase)

    factoryOf(::ChecklistUseCase)

    factoryOf(::SearchUseCase)

    // PomodoroTimer is registered in platform-specific modules:
    // - androidMain: AndroidPomodoroTimer(get(), get(), get())
    // - jvmMain: JvmPomodoroTimer()

    // ─── Ports ──────────────────────────────────────────────────────────

    single<AttachmentSaver> { AttachmentSaverImpl(get()) }

    // ─── Reminders ──────────────────────────────────────────────────────

    factory { ReminderScheduler(Logger.withTag("ReminderScheduler"), get(), get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { (taskId: com.singularity.todo.feature.tasks.domain.model.TaskId) ->
        TaskDetailViewModel(
            deps = TaskDetailDeps(
                taskRepo = get(),
                updateTask = get(),
                createTask = get(),
                projectsRepo = get(),
                tagsRepo = get(),
                checklistRepository = get(),
                checklistUseCase = get(),
                reminderRepo = get(),
                attachmentsRepo = get(),
                timeZoneProvider = get(),
                clock = get(),
            ),
            taskId = taskId,
            currentUser = get(),
        )
    }

    viewModel { (initialDueDate: kotlinx.datetime.LocalDate?) ->
        TaskCreateViewModel(
            deps = TaskCreateDeps(
                createFromDraft = get(),
                currentUser = get(),
                logger = Logger.withTag("TaskCreate"),
                draftStore = get(),
            ),
            initialDueDate = initialDueDate,
        )
    }

    viewModel { ChecklistEditorViewModel(checklistUseCase = get(), checklistRepository = get()) }

    viewModel { ArchiveViewModel(archiveRepo = get(), taskRepo = get()) }

    viewModel { StatisticsViewModel(taskRepository = get(), clock = get()) }

    viewModel { SearchViewModel(searchUseCase = get(), taskRepo = get()) }
}
