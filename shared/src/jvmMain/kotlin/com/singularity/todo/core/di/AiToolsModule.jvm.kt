package com.singularity.todo.core.di

import ai.koog.prompt.llm.LLModel
import co.touchlab.kermit.Logger
import com.singularity.todo.core.llm.KnownModels
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.security.ProfileAwareSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.KoogAgentService
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.ai.chat.ChatViewModel
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.CreateNoteTool
import com.singularity.todo.feature.ai.tools.CreateProjectTool
import com.singularity.todo.feature.ai.tools.CreateTagTool
import com.singularity.todo.feature.ai.tools.CreateTaskTool
import com.singularity.todo.feature.ai.tools.DecomposeAndCreateTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.DeleteNoteTool
import com.singularity.todo.feature.ai.tools.DeleteProjectTool
import com.singularity.todo.feature.ai.tools.DeleteTagTool
import com.singularity.todo.feature.ai.tools.DeleteTaskTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.ListAdrsTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListProjectsTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.ReadAdrTool
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.ai.tools.UpdateProjectTool
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.WriteAdrTool
import com.singularity.todo.feature.ai.usage.AiUsageViewModel
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.genui.GenuiEngine
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.transport.GenuiTransport
import com.singularity.todo.feature.genui.transport.KoogGenuiTransport
import com.singularity.todo.feature.profile.ProfileSwitcherViewModel
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * JVM actual for [aiToolsModule].
 *
 * Combines all shared AI bindings with the JVM-specific
 * Koog executor and LLM into a single module (no `includes()` to avoid child-scope
 * isolation in Koin 4).
 */
actual fun aiToolsModule(): Module = module {
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
            allTools = get(),
            remoteConfigPort = get(),
        )
    }

    // ─── Token Usage Tracking ───

    singleOf(::RoomUsageRecorder)

    viewModel { ChatViewModel(Logger.withTag("ChatViewModel"), get(), get()) }
    viewModel { AiUsageViewModel(get(), get()) }
    viewModel { ProfileSwitcherViewModel(get()) }

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

    factory { RefineTaskTool(get(), get()) }
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
    factory { ListLinkedTasksTool(get<TaskRepository>()) }
    factory { ListTasksTool(get<TaskRepository>()) }
    factory { SearchTasksTool(get<TaskRepository>()) }
    factory { CreateTaskTool(get(), get(), get()) }
    factory { UpdateTaskTool(get(), get()) }
    factory { DeleteTaskTool(get()) }
    factory { CreateNoteTool(get(), get(), get()) }
    factory { UpdateNoteTool(get(), get()) }
    factory { DeleteNoteTool(get()) }
    factory { CreateProjectTool(get(), get(), get()) }
    factory { UpdateProjectTool(get(), get()) }
    factory { DeleteProjectTool(get()) }
    factory { ListProjectsTool(get()) }
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
        ProjectsViewModel(
            projectRepo = get<ProjectsRepository>(),
            taskRepository = get<TaskRepository>(),
            projectReview = getOrNull(),
            deleteProject = get<DeleteProjectUseCase>(),
        )
    }

    // ─── JVM-specific bindings ───────────────────────────────────────────────

    // Default LLM. Built via the public LLModel constructor rather than
    // OpenAIModels.Chat.GPT4oMini to avoid triggering OpenAIModels.<clinit>
    // in the JVM-test classpath.
    single<LLModel> { KnownModels.GPT4oMini }

    // Real Koog executor on JVM
    single<PromptExecutorPort> {
        koinBridge {
            createKoogPromptExecutor(get<SecureStoragePort>(), get<SettingsRepository>())
        }
    }

    // Raw Koog executor for AI tools
    single<ai.koog.prompt.executor.model.PromptExecutor> {
        get<PromptExecutorPort>().executor
    }
}
