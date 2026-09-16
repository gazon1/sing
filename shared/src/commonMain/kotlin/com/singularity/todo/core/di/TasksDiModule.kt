package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.clock.DelayAutosaveScheduler
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.archive.ArchiveRepository
import com.singularity.todo.feature.archive.ArchiveViewModel
import com.singularity.todo.feature.archive.TaskDaoArchiveRepository
import com.singularity.todo.feature.checklist.ChecklistEditorViewModel
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.checklist.RoomChecklistRepository
import com.singularity.todo.feature.pomodoro.InMemoryPomodoroRepository
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
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TasksViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Tasks feature DI: repositories, use cases, ViewModels.
 * Does NOT include AI tools — those live in [aiToolsCoreModule].
 *
 * Navigation entries (navigation{}) live in platform-specific sources:
 * - androidMain: TasksNavEntries.kt (Nav3)
 * - jvmMain: TasksNavEntries.kt (Nav3)
 * Both delegate to the ViewModels and Screens defined here.
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

    // PomodoroTimer is registered in platform-specific modules:
    // - androidMain: AndroidPomodoroTimer(get(), get(), get())
    // - jvmMain: JvmPomodoroTimer()

    // ─── Autosave ───────────────────────────────────────────────────────

    single<AutosaveScheduler> { DelayAutosaveScheduler() }

    // ─── Ports ──────────────────────────────────────────────────────────

    factory<IdGenerator> { UlidIdGenerator }

    single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

    single<AttachmentSaver> { AttachmentSaverImpl(get(), get()) }

    // ─── Reminders ──────────────────────────────────────────────────────

    factory { ReminderScheduler(co.touchlab.kermit.Logger.withTag("ReminderScheduler"), get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    // NOTE: Using explicit viewModel {} block instead of viewModelOf so that
    // sharingStarted and scopeOverride use their defaults. viewModelOf(::TasksViewModel)
    // uses reflection to resolve all constructor parameters and can incorrectly match
    // CoroutineScope beans (CoreDiModule) against the () -> SharingStarted parameter,
    // causing ClassCastException at runtime.
    viewModel {
        TasksViewModel(
            taskRepo = get(),
            createTask = get(),
            updateTask = get(),
            currentUser = get(),
            mutations = get(),
            projectRepo = get(),
            clock = get(),
        )
    }

    viewModel { (taskId: com.singularity.todo.feature.tasks.domain.model.TaskId) ->
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
                clock = get(),
            ),
            taskId = taskId,
        )
    }

    viewModel { (initialDueDate: kotlinx.datetime.LocalDate?) ->
        TaskCreateViewModel(
            deps = TaskCreateDeps(
                createTask = get(),
                currentUser = get(),
                logger = Logger.withTag("TaskCreate"),
                draftStore = get(),
                autosaveScheduler = get(),
            ),
            initialDueDate = initialDueDate,
        )
    }

    viewModelOf(::ChecklistEditorViewModel)

    viewModelOf(::ArchiveViewModel)

    viewModelOf(::StatisticsViewModel)

    viewModelOf(::SearchViewModel)
}
