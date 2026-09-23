package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.draft.UserScopedDraftStore
import com.singularity.todo.feature.archive.ArchiveViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.archive.TaskDaoArchiveRepository
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.checklist.RoomChecklistRepository
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.SearchViewModel
import com.singularity.todo.feature.search.data.RoomSavedSearchRepository
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import com.singularity.todo.feature.search.query.DaoProjectLookup
import com.singularity.todo.feature.search.query.DaoTagLookup
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.TagLookup
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
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

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

    single<TaskRepository> { TaskRepositoryImpl(get(), get(), get(), get()) }

    singleOf(::TaskDaoArchiveRepository)

    single<ChecklistRepository> { RoomChecklistRepository(get(), get()) }

    single<SavedSearchRepository> { RoomSavedSearchRepository(get(), get(), get()) }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateTaskUseCase(get(), get(), get()) }
    factory { CreateTaskFromDraftUseCase(get(), get(), get()) }
    factory { UpdateTaskUseCase(get(), get()) }
    factoryOf(::TaskMutationsUseCase)

    factory { SearchUseCase(get(), get(), get(), get(), get()) }

    single<TagLookup> { DaoTagLookup(get()) }
    single<ProjectLookup> { DaoProjectLookup(get()) }
    single<SearchQueryResolver> { DefaultSearchQueryResolver(get(), get()) }

    // PomodoroTimer is registered in platform-specific modules:
    // - androidMain: AndroidPomodoroTimer(get(), get(), get())
    // - jvmMain: JvmPomodoroTimer()

    // ─── Ports ──────────────────────────────────────────────────────────

    single<AttachmentSaver> { AttachmentSaverImpl(get()) }

    // ─── Drafts ──────────────────────────────────────────────────────────
    // UserScopedDraftStore wraps DraftStore, prepending the user ID prefix internally.
    // Registered as DraftStore so it satisfies TaskCreateDeps.draftStore: DraftStore.
    single<DraftStore> { UserScopedDraftStore(get(), get()) }

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
                reminderRepo = get(),
                reminderScheduler = get(),
                attachmentsRepo = get(),
                timeZoneProvider = get(),
                clock = get(),
            ),
            taskId = taskId,
        )
    }

    viewModel { (initialDueDate: kotlinx.datetime.LocalDate?) ->
        TaskCreateViewModel(
            deps = TaskCreateDeps(
                createFromDraft = get(),
                logger = Logger.withTag("TaskCreate"),
                draftStore = get(),
            ),
            initialDueDate = initialDueDate,
        )
    }

    viewModel { ArchiveViewModel(archiveRepo = get(), taskRepo = get()) }

    viewModel { StatisticsViewModel(taskRepository = get(), clock = get()) }

    viewModel<SearchViewModel> {
        SearchViewModel(
            searchUseCase = get(),
            savedSearchRepo = get(),
            clock = get(),
        )
    }
}
