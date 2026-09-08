package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.security.ProfileAwareSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.ai.KoogAgentService
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.DecomposeAndCreateTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.tools.CreateTaskTool
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.ai.tools.DeleteTaskTool
import com.singularity.todo.feature.ai.tools.CreateNoteTool
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.ai.tools.DeleteNoteTool
import com.singularity.todo.feature.ai.tools.CreateProjectTool
import com.singularity.todo.feature.ai.tools.UpdateProjectTool
import com.singularity.todo.feature.ai.tools.DeleteProjectTool
import com.singularity.todo.feature.ai.tools.ListProjectsTool
import com.singularity.todo.feature.ai.tools.CreateTagTool
import com.singularity.todo.feature.ai.tools.DeleteTagTool
import com.singularity.todo.feature.ai.tools.ListAdrsTool
import com.singularity.todo.feature.ai.tools.ReadAdrTool
import com.singularity.todo.feature.ai.tools.WriteAdrTool
import com.singularity.todo.feature.ai.usage.AiUsageViewModel
import com.singularity.todo.feature.profile.ProfileSwitcherViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.ai.chat.ChatViewModel
import com.singularity.todo.feature.tasks.TasksViewModel
import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.genui.GenuiEngine
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.transport.GenuiTransport
import com.singularity.todo.feature.genui.transport.KoogGenuiTransport
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.projects.CreateProjectUseCase
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.observability.UsageRecorder
import com.singularity.todo.core.platform.Clock
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * AI tools, GenUI, and AI use cases — shared between JVM and Android.
 * Platform-specific [aiToolsModule] actuals include these bindings plus their
 * platform executor / LLM bindings.
 */
internal fun aiToolsCoreModule(): org.koin.core.module.Module = module {
    // ─── Profile-aware secure storage for AI ────────────────────────────────
    factory<ProfileAwareSecureStorage> {
        ProfileAwareSecureStorage(get(), get())
    }

    // ─── AI Service ───

    single<TextGenPort> {
        KoogAgentService(
            secureStorage = get<ProfileAwareSecureStorage>(),
            settings = get(),
            promptExecutor = get<PromptExecutorPort>().executor,
            streamingExecutor = get(),
            tools = get(),
        )
    }

    // ─── Token Usage Tracking ───

    single<UsageRecorder> { RoomUsageRecorder(get(), get<Clock>()) }

    viewModelOf(::ChatViewModel)
    viewModelOf(::AiUsageViewModel)
    viewModelOf(::ProfileSwitcherViewModel)

    // ─── GenUI ───

    single { SurfaceController() }
    single { A2uiParser() }

    factory<GenuiTransport> { KoogGenuiTransport(get()) }

    factory {
        GenuiEngine(
            transport = get(),
            parser = get(),
            controller = get(),
        )
    }

    // ─── AI Use Cases ───

    factory { RefineTaskUseCase(get()) }
    factory { SmartRewriteUseCase(get()) }
    factory { GenerateDescriptionUseCase(get()) }
    factory { DecomposeTaskUseCase(get()) }
    factory { GenerateChecklistUseCase(get()) }
    factory { PickTimeUseCase(get()) }
    factory { ClusterTasksUseCase(get()) }
    factory { ClusterNotesUseCase(get()) }
    factory { ImproveNoteUseCase(tool = get<ImproveNoteTool>()) }
    factory { ProjectReviewUseCase(get()) }

    // ─── AI Tools ───

    factory { RefineTaskTool(Logger.withTag("RefineTaskTool"), get(), get()) }
    factory { SmartRewriteTool(get(), get()) }
    factory { GenerateDescriptionTool(get(), get()) }
    factory { DecomposeTaskTool(get(), get()) }
    factory { DecomposeAndCreateTool(get(), get(), get(), get(), get()) }
    factory { GenerateChecklistTool(get(), get()) }
    factory { PickTimeTool(get(), get()) }
    factory { ClusterTasksTool(get(), get()) }
    factory { ClusterNotesTool(get(), get()) }
    factory { ProjectReviewTool(get(), get()) }
    factory { WeeklyPlanTool(get(), get()) }
    factory { ImproveNoteTool(get(), get()) }
    factory { GetNoteTool(get()) }
    factory { GetProjectTool(get()) }
    factory { GetTaskTool(get()) }
    factory { ListLinkedTasksTool(get(), get()) }
    factory { ListTasksTool(get(), get()) }
    factory { SearchTasksTool(get(), get()) }
    factory { CreateTaskTool(get(), get(), get()) }
    factory { UpdateTaskTool(get(), get()) }
    factory { DeleteTaskTool(get()) }
    factory { CreateNoteTool(get(), get(), get()) }
    factory { UpdateNoteTool(get(), get()) }
    factory { DeleteNoteTool(get()) }
    factory { CreateProjectTool(get(), get(), get()) }
    factory { UpdateProjectTool(get(), get()) }
    factory { DeleteProjectTool(get(), get()) }
    factory { ListProjectsTool(get(), get()) }
    factory { CreateTagTool(get(), get(), get()) }
    factory { DeleteTagTool(get()) }
    factory { ListAdrsTool() }
    factory { ReadAdrTool() }
    factory { WriteAdrTool() }

    // ─── AI tools list for KoogAgentService ───

    single<List<ai.koog.agents.core.tools.Tool<*, *>>> {
        listOf(
            get<RefineTaskTool>(),
            get<SmartRewriteTool>(),
            get<GenerateDescriptionTool>(),
            get<DecomposeTaskTool>(),
            get<GenerateChecklistTool>(),
            get<PickTimeTool>(),
            get<ClusterTasksTool>(),
            get<ClusterNotesTool>(),
            get<ProjectReviewTool>(),
            get<WeeklyPlanTool>(),
            get<ImproveNoteTool>(),
            get<GetNoteTool>(),
            get<GetProjectTool>(),
            get<GetTaskTool>(),
            get<ListLinkedTasksTool>(),
            get<ListTasksTool>(),
            get<SearchTasksTool>(),
            get<CreateTaskTool>(),
            get<UpdateTaskTool>(),
            get<DeleteTaskTool>(),
            get<CreateNoteTool>(),
            get<UpdateNoteTool>(),
            get<DeleteNoteTool>(),
            get<CreateProjectTool>(),
            get<UpdateProjectTool>(),
            get<DeleteProjectTool>(),
            get<ListProjectsTool>(),
            get<CreateTagTool>(),
            get<DeleteTagTool>(),
            get<ListAdrsTool>(),
            get<ReadAdrTool>(),
            get<WriteAdrTool>(),
            get<DecomposeAndCreateTool>(),
        )
    }

    // ─── ViewModels with nullable AI deps ───────────────────────────────

    viewModel {
        TasksViewModel(
            taskRepo = get<TaskRepository>(),
            createTask = get<CreateTaskUseCase>(),
            updateTask = get<UpdateTaskUseCase>(),
            currentUser = get<ProfileAwareCurrentUser>(),
            mutations = get<TaskMutationsUseCase>(),
            refineTask = getOrNull(),
            generateDescription = getOrNull(),
            generateChecklist = getOrNull(),
            decomposeTask = getOrNull(),
            pickTime = getOrNull(),
        )
    }

    viewModel {
        ProjectsViewModel(
            projectRepo = get<ProjectsRepository>(),
            createProject = get<CreateProjectUseCase>(),
            currentUser = get<ProfileAwareCurrentUser>(),
            taskRepository = get<TaskRepository>(),
            projectReview = getOrNull(),
            deleteProject = get<DeleteProjectUseCase>(),
        )
    }
}
