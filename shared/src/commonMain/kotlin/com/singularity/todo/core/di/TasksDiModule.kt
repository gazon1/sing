@file:Suppress("ImportOrdering", "LongMethod")

package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.draft.UserScopedDraftStore
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.tasks.FixedProfileTasksSettingsRepository
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.archive.ArchiveViewModel
import com.singularity.todo.feature.archive.domain.port.ArchiveRepository
import com.singularity.todo.feature.archive.data.TaskDaoArchiveRepositoryImpl
import com.singularity.todo.feature.checklist.data.ChecklistRepositoryImpl
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.SearchViewModel
import com.singularity.todo.feature.search.data.SavedSearchRepositoryImpl
import com.singularity.todo.feature.search.domain.port.SavedSearchRepository
import com.singularity.todo.feature.search.query.DaoProjectLookup
import com.singularity.todo.feature.search.query.DaoTagLookup
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.SearchQueryResolver
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.statistics.StatisticsDeps
import com.singularity.todo.feature.statistics.StatisticsViewModel
import com.singularity.todo.feature.tasks.data.AttachmentSaverImpl
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.AttachmentSaver
import com.singularity.todo.feature.tasks.domain.model.TaskAiDeps
import com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCollaborationDeps
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskSchedulingDeps
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskReopenUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.tasks.domain.usecase.DetachTaskFromSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MergeTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.RepairRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.StartRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.nav.TaskDeepLink
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The task settings the tools read.
 *
 * ## Why the tools do not ask for the whole settings repository
 *
 * The application's settings live in the same store as the active profile id. A
 * process that was told which profile to serve — the tool server an external agent
 * talks to — must not open that store: the application is holding it, and the point of
 * the fixed profile is that this process does not go near the application's stored
 * profile state.
 *
 * The tools need exactly one setting, and they ask for it through the narrow
 * [TasksSettingsRepository] port. That port is what makes the isolation possible: a
 * tool that asked for the whole settings repository would pull the store in with it,
 * and the whole graph would open the file the application is holding.
 *
 * So the answer depends on the mode:
 * - **the application** gets the real setting, read from the real store;
 * - **a process told which profile to serve** gets the default, and opens nothing.
 *
 * @param fixedProfileId the profile this process works as, or null to use the stored one.
 */
fun tasksSettingsModule(fixedProfileId: ProfileId?): org.koin.core.module.Module = module {
    single<TasksSettingsRepository> {
        if (fixedProfileId != null) {
            FixedProfileTasksSettingsRepository()
        } else {
            get<SettingsRepository>()
        }
    }
}

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

    single<TaskRepository> {
        TaskRepositoryImpl(
            taskDao = get(),
            textIndex = get(),
            clock = get(),
            currentUser = get(),
            syncRepository = get(),
            unitOfWork = get(),
            dependencyValidator = get(),
        )
    }

    single<DependencyValidator> { DependencyValidatorImpl(get(), get<ProfileAwareCurrentUser>()) }

    single { RecurrenceCalculator }

    single<ArchiveRepository> { TaskDaoArchiveRepositoryImpl(get(), get(), get(), get(), get()) }

    single<ChecklistRepository> { ChecklistRepositoryImpl(get(), get(), get()) }

    // Two arguments, not three. The third was a `clock` parameter that
    // `SavedSearchRepositoryImpl` declared and never read (#192); `get()` was
    // resolving a `Clock` and handing it to a field nothing read, so removing it
    // changed no behaviour and made the Koin graph match the constructor.
    single<SavedSearchRepository> { SavedSearchRepositoryImpl(get(), get()) }

    // ─── Time tracking ──────────────────────────────────────────────────
    single<com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository> {
        com.singularity.todo.feature.timetracking.data.TimeTrackingRepositoryImpl(
            get(),
            get(),
            get<ProfileAwareCurrentUser>(),
        )
    }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateTaskUseCase(get(), get(), get()) }
    factory { CreateTaskFromDraftUseCase(get(), get(), get(), get(), get(), get(), get()) }
    factory { UpdateTaskUseCase(get(), get()) }
    factory { MaterializeRecurringSeriesUseCase(get(), get(), get()) }
    factory { StartRecurringSeriesUseCase(get(), get(), get(), get()) }
    factory { UpdateTaskSeriesUseCase(get(), get(), get()) }
    factory { DetachTaskFromSeriesUseCase(get()) }
    factory { MergeTaskSeriesUseCase(get()) }
    factory { CompleteRecurringTaskUseCase(get(), get(), get(), get(), get(), get()) }
    factory { RepairRecurringSeriesUseCase(get(), get(), get(), get()) }
    factoryOf(::TaskMutationsUseCase)

    // The rule of opening a task: it brings its closed project back with it.
    factory { TaskReopenUseCase(get(), get(), get()) }

    factory {
        SearchUseCase(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    single<TagLookup> { DaoTagLookup(get()) }
    single<ProjectLookup> { DaoProjectLookup(get()) }
    single<SearchQueryResolver> {
        DefaultSearchQueryResolver(get(), get(), get(), get())
    }

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

    viewModel { (route: com.singularity.todo.feature.nav.TasksRoute.Detail) ->
        TaskDetailCoordinator(
            deps = TaskDetailDeps(
                core = TaskCoreDeps(
                    taskRepo = get(),
                    updateTask = get(),
                    createTask = get(),
                    completeRecurring = get(),
                    completion = get(),
                    updateSeries = get(),
                    detachFromSeries = get(),
                    startRecurringSeries = get(),
                ),
                children = TaskChildrenDeps(
                    checklistRepository = get(),
                    attachmentsRepo = get(),
                    projectsRepo = get(),
                    tagsRepo = get(),
                ),
                scheduling = TaskSchedulingDeps(
                    reminderRepo = get(),
                    reminderScheduler = get(),
                    timeZoneProvider = get(),
                ),
                collaboration = TaskCollaborationDeps(
                    notesRepo = get(),
                    timeTrackingRepo = get(),
                    currentUser = get<ProfileAwareCurrentUser>(),
                    linkRepo = get(),
                    proposals = get(),
                    applyProposal = get(),
                    editProposal = get(),
                ),
                ai = TaskAiDeps(
                    refineTask = get(),
                    generateDescription = get(),
                    generateChecklist = get(),
                    decomposeTask = get(),
                    pickTime = get(),
                    firstStep = get(),
                ),
                context = TaskContextDeps(clock = get()),
            ),
            taskId = route.taskId,
            sourceViewId = route.sourceViewId,
            crashReporter = get(),
        )
    }

    viewModel { (initialDueDate: kotlinx.datetime.LocalDate?, draftKey: String?) ->
        TaskCreateViewModel(
            deps = TaskCreateDeps(
                createFromDraft = get(),
                logger = Logger.withTag("TaskCreate"),
                draftStore = get(),
            ),
            initialDueDate = initialDueDate,
            draftKey = draftKey,
            crashReporter = get(),
        )
    }

    viewModel { ArchiveViewModel(archiveRepo = get(), taskRepo = get(), crashReporter = get()) }

    viewModel<StatisticsViewModel> {
        StatisticsViewModel(
            deps = StatisticsDeps(
                taskRepository = get(),
                timeTrackingRepo = get(),
                projectsRepo = get(),
                currentUser = get(),
                clock = get(),
                settingsRepository = get(),
            ),
            crashReporter = get(),
        )
    }

    viewModel<SearchViewModel> {
        SearchViewModel(
            searchUseCase = get(),
            savedSearchRepo = get(),
            taskRepo = get(),
            clock = get(),
            crashReporter = get(),
        )
    }

    // ─── Deep link ──────────────────────────────────────────────────────
    // The task link is owned by this feature: it declares the URI template and
    // the mapping to the task detail screen. The registry that collects the
    // links lives in `feature/nav/deeplink`.
    single { TaskDeepLink() }
}
