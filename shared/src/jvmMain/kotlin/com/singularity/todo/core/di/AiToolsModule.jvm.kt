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
import com.singularity.todo.feature.ai.chat.UsageRecordingTextGen
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
import com.singularity.todo.feature.ai.tools.ExtractActionsTool
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
import com.singularity.todo.feature.ai.tools.RewriteNoteTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.SuggestTagsTool
import com.singularity.todo.feature.ai.tools.SummarizeNoteTool
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.ai.tools.UpdateProjectTool
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.WriteAdrTool
import com.singularity.todo.feature.ai.usage.AiUsageViewModel
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.ExtractActionsUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.RewriteNoteUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.ai.use_cases.SuggestTagsUseCase
import com.singularity.todo.feature.ai.use_cases.SummarizeNoteUseCase
import com.singularity.todo.feature.genui.GenuiEngine
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.transport.GenuiTransport
import com.singularity.todo.feature.genui.transport.KoogGenuiTransport
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileSwitcherViewModel
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * JVM actual for [aiToolsModule].
 *
 * Combines all shared AI bindings with the JVM-specific
 * Koog executor and LLM into a single module (no `includes()` to avoid child-scope
 * isolation in Koin 4).
 */
actual fun aiToolsModule(): Module = module {
    // ─── Profile-aware secure storage for AI ────────────────────────────────

    // Interface bindings stay as explicit lambdas: `factoryOf(::Impl)` binds the concrete
    // type, so it cannot express `factory<ProfileAwareSecureStorage> { … }` or
    // `factory<GenuiTransport> { … }` below. `ImproveNoteUseCase(tool = …)` keeps its
    // named argument for the same reason — `factoryOf` passes positionally only.
    factory<ProfileAwareSecureStorage> {
        ProfileAwareSecureStorage(get(), get())
    }

    // ─── AI Service ───

    single<TextGenPort> {
        val delegate = KoogAgentService(
            secureStorage = get<ProfileAwareSecureStorage>(),
            settings = get(),
            promptExecutor = get<PromptExecutorPort>().executor,
            streamingExecutor = get(),
            allTools = get(),
            remoteConfigPort = get(),
        )
        UsageRecordingTextGen(
            delegate = delegate,
            usageRecorder = get<RoomUsageRecorder>(),
            currentUser = get<ProfileAwareCurrentUser>(),
            clock = get<Clock>(),
        )
    }

    // ─── Token Usage Tracking ───

    singleOf(::RoomUsageRecorder)
    @Suppress("NoDirectClockSystem") // Clock.System wrapped for injectability
    single<Clock> { Clock.System }

    viewModel { ChatViewModel(Logger.withTag("ChatViewModel"), get(), get(), get()) }
    viewModel { AiUsageViewModel(usageRecorder = get(), profileRepository = get(), crashReporter = get()) }
    viewModel { ProfileSwitcherViewModel(profileRepository = get(), crashReporter = get()) }

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

    factoryOf(::RefineTaskUseCase)
    factoryOf(::SmartRewriteUseCase)
    factoryOf(::GenerateDescriptionUseCase)
    factoryOf(::DecomposeTaskUseCase)
    factoryOf(::GenerateChecklistUseCase)
    factoryOf(::PickTimeUseCase)
    factoryOf(::ClusterTasksUseCase)
    factoryOf(::ClusterNotesUseCase)
    factory { ImproveNoteUseCase(tool = get<ImproveNoteTool>()) }
    factoryOf(::SummarizeNoteUseCase)
    factoryOf(::ExtractActionsUseCase)
    factoryOf(::RewriteNoteUseCase)
    factoryOf(::SuggestTagsUseCase)
    factoryOf(::ProjectReviewUseCase)

    // ─── AI Tools ───

    factoryOf(::RefineTaskTool)
    factoryOf(::SmartRewriteTool)
    factoryOf(::GenerateDescriptionTool)
    factoryOf(::DecomposeTaskTool)
    factoryOf(::DecomposeAndCreateTool)
    factoryOf(::GenerateChecklistTool)
    factoryOf(::PickTimeTool)
    factoryOf(::ClusterTasksTool)
    factoryOf(::ClusterNotesTool)
    factoryOf(::ProjectReviewTool)
    factoryOf(::WeeklyPlanTool)
    factoryOf(::ImproveNoteTool)
    factoryOf(::SummarizeNoteTool)
    factoryOf(::ExtractActionsTool)
    factoryOf(::RewriteNoteTool)
    factoryOf(::SuggestTagsTool)
    factoryOf(::GetNoteTool)
    factoryOf(::GetProjectTool)
    factoryOf(::GetTaskTool)
    factoryOf(::ListLinkedTasksTool)
    factoryOf(::ListTasksTool)
    factoryOf(::SearchTasksTool)
    factoryOf(::CreateTaskTool)
    factoryOf(::UpdateTaskTool)
    factory { DeleteTaskTool(get<ProposalRepository>(), get<ProfileAwareCurrentUser>(), get<Clock>()) }
    factoryOf(::CreateNoteTool)
    factoryOf(::UpdateNoteTool)
    factory { DeleteNoteTool(get<ProposalRepository>(), get<ProfileAwareCurrentUser>(), get<Clock>()) }
    factoryOf(::CreateProjectTool)
    factoryOf(::UpdateProjectTool)
    factory { DeleteProjectTool(get<ProposalRepository>(), get<ProfileAwareCurrentUser>(), get<Clock>()) }
    factoryOf(::ListProjectsTool)
    factoryOf(::CreateTagTool)
    factory { DeleteTagTool(get<ProposalRepository>(), get<ProfileAwareCurrentUser>(), get<Clock>()) }
    factoryOf(::ListAdrsTool)
    factoryOf(::ReadAdrTool)
    factoryOf(::WriteAdrTool)

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
            get<SummarizeNoteTool>(),
            get<ExtractActionsTool>(),
            get<RewriteNoteTool>(),
            get<SuggestTagsTool>(),
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
            crashReporter = get(),
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
