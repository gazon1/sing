package com.singularity.todo.core.di

import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.notes.CreateNoteUseCase
import com.singularity.todo.feature.notes.NotesViewModel
import com.singularity.todo.feature.notes.RichEditorMarkdownHtmlPort
import com.singularity.todo.feature.notes.RoomNotesRepository
import com.singularity.todo.feature.notes.UpdateNoteUseCase
import com.singularity.todo.feature.projects.CreateProjectUseCase
import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.projects.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.UpdateProjectUseCase
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.reminders.RoomReminderRepository
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.settings.SettingsViewModel
import com.singularity.todo.feature.tags.CreateTagUseCase
import com.singularity.todo.feature.tags.TagsViewModel
import com.singularity.todo.feature.tags.TagsRepositoryImpl
import com.singularity.todo.feature.tags.UpdateTagUseCase
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.TasksViewModel
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
import com.singularity.todo.feature.ai.tools.ClusterNotesInput
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksInput
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskInput
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistInput
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionInput
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.GetNoteInput
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectInput
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskInput
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksInput
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksInput
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.PickTimeInput
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ProjectReviewInput
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.RefineTaskInput
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SearchTasksInput
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import ai.koog.agents.core.tools.SimpleTool
import com.singularity.todo.feature.ai.tools.SmartRewriteInput
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanInput
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.dataGetNoteTool
import com.singularity.todo.feature.ai.tools.dataGetProjectTool
import com.singularity.todo.feature.ai.tools.dataGetTaskTool
import com.singularity.todo.feature.ai.tools.dataListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.dataListTasksTool
import com.singularity.todo.feature.ai.tools.dataSearchTasksTool
import com.singularity.todo.feature.ai.tools.llmClusterNotesTool
import com.singularity.todo.feature.ai.tools.llmClusterTasksTool
import com.singularity.todo.feature.ai.tools.llmDecomposeTaskTool
import com.singularity.todo.feature.ai.tools.llmGenerateChecklistTool
import com.singularity.todo.feature.ai.tools.llmGenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.llmPickTimeTool
import com.singularity.todo.feature.ai.tools.llmProjectReviewTool
import com.singularity.todo.feature.ai.tools.llmRefineTaskTool
import com.singularity.todo.feature.ai.tools.llmSmartRewriteTool
import com.singularity.todo.feature.ai.tools.llmWeeklyPlanTool
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import com.singularity.todo.feature.ai.KoogAgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Returns all domain-level bindings as a Koin [Module].
 *
 * Platform-specific bindings come from [platformModule]:
 * - Database DAOs — androidMain / jvmMain
 * - [com.singularity.todo.core.security.SecureStoragePort]
 * - [com.singularity.todo.core.notifications.NotificationPort]
 * - [com.singularity.todo.core.files.FileSystem]
 * - [com.singularity.todo.core.backup.BackupCodec]
 * - [PromptExecutorPort]
 * - [androidx.datastore.core.DataStore]
 *
 * Usage:
 * ```
 * modules(domainModule(), platformModule())
 * ```
 */
fun domainModule(): Module = module {
    // ─── Scopes ───────────────────────────────────────────────────────────

    single {
        { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    }

    single {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    // ─── Settings ────────────────────────────────────────────────────────

    single<com.singularity.todo.core.settings.SettingsRepository> {
        DataStoreSettingsRepository(get())
    }

    // ─── Session ────────────────────────────────────────────────────────

    single<com.singularity.todo.core.auth.SessionStore> {
        DataStoreSessionStore(get())
    }

    single<com.singularity.todo.core.auth.AuthRepository> {
        SupabaseAuthRepository(get(), Dispatchers.IO)
    }

    // ─── Repositories ─────────────────────────────────────────────────────

    single<com.singularity.todo.feature.tasks.TaskRepository> {
        TaskRepositoryImpl(get(), get())
    }

    single<com.singularity.todo.feature.notes.NotesRepository> {
        RoomNotesRepository(get(), get())
    }

    single<com.singularity.todo.feature.projects.ProjectsRepository> {
        ProjectsRepositoryImpl(get(), get())
    }

    single<com.singularity.todo.feature.tags.TagsRepository> {
        TagsRepositoryImpl(get(), get())
    }

    single<com.singularity.todo.core.attachments.AttachmentRepository> {
        AttachmentRepositoryImpl(get(), get(), get(), get())
    }

    single<com.singularity.todo.feature.reminders.ReminderRepository> {
        RoomReminderRepository(get())
    }

    // Platform clock singleton — actual implementation is in androidMain/jvmMain
    single { Clock }
    // ─── Ports ───────────────────────────────────────────────────────────

    single<com.singularity.todo.feature.notes.MarkdownHtmlPort> { RichEditorMarkdownHtmlPort() }

    single<com.singularity.todo.core.attachments.AttachmentUploadService> { StubAttachmentUploadService() }

    single<com.singularity.todo.core.backup.RemoteBackupService> { StubRemoteBackupService() }

    // ─── Sync ───────────────────────────────────────────────────────────

    single { HlcFactory(get(), get()) }

    single<com.singularity.todo.core.sync.SyncApiClient> { SupabaseSyncApiClient() }

    single { com.singularity.todo.core.sync.SyncEngine(get(), get(), get(), get(), get()) }

    // ─── Reminders ──────────────────────────────────────────────────────

    factory { ReminderScheduler(get(), get()) }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateTaskUseCase(get(), get()) }
    factory { UpdateTaskUseCase(get(), get()) }

    factory { CreateNoteUseCase(get(), get()) }
    factory { UpdateNoteUseCase(get(), get()) }

    factory { CreateProjectUseCase(get(), get()) }
    factory { UpdateProjectUseCase(get(), get()) }

    factory { CreateTagUseCase(get(), get()) }
    factory { UpdateTagUseCase(get(), get()) }

    factory { SearchUseCase(get(), get(), get(), get()) }

    // ─── AI Tools ───────────────────────────────────────────────────────

    // Default LLM used by all LLM-based tools
    single<LLModel> { OpenAIModels.Chat.GPT4oMini }

    // LLM tools (require PromptExecutor + model)
    // Old class-based registrations — needed by use cases (RefineTaskUseCase etc.)
    factory { RefineTaskTool(get(), get()) }
    factory { SmartRewriteTool(get(), get()) }
    factory { GenerateDescriptionTool(get(), get()) }
    factory { DecomposeTaskTool(get(), get()) }
    factory { GenerateChecklistTool(get(), get()) }
    factory { PickTimeTool(get(), get()) }
    factory { ClusterTasksTool(get(), get()) }
    factory { ClusterNotesTool(get(), get()) }
    factory { ProjectReviewTool(get(), get()) }
    factory { WeeklyPlanTool(get(), get()) }
    factory { ImproveNoteTool(get(), get()) }

    // Read-only tools (use repositories) — needed by use cases
    factory { GetNoteTool(get()) }
    factory { GetProjectTool(get()) }
    factory { GetTaskTool(get()) }
    factory { ListLinkedTasksTool(get()) }
    factory { ListTasksTool(get()) }
    factory { SearchTasksTool(get()) }

    // Factory-based registrations — for KoogAgentService list
    factory { llmRefineTaskTool()(get(), get()) }
    factory { llmSmartRewriteTool()(get(), get()) }
    factory { llmGenerateDescriptionTool()(get(), get()) }
    factory { llmDecomposeTaskTool()(get(), get()) }
    factory { llmGenerateChecklistTool()(get(), get()) }
    factory { llmPickTimeTool()(get(), get()) }
    factory { llmClusterTasksTool()(get(), get()) }
    factory { llmClusterNotesTool()(get(), get()) }
    factory { llmProjectReviewTool(get(), get()) }
    factory { llmWeeklyPlanTool()(get(), get()) }

    // Read-only tools via factory (for list)
    factory { dataGetNoteTool(get())() }
    factory { dataGetProjectTool(get())() }
    factory { dataGetTaskTool(get())() }
    factory { dataListLinkedTasksTool(get())() }
    factory { dataListTasksTool(get())() }
    factory { dataSearchTasksTool(get())() }

    // All AI tools collected into a list for KoogAgentService
    single<List<Tool<*, *>>> {
        listOf(
            get<SimpleTool<RefineTaskInput>>(),
            get<SimpleTool<SmartRewriteInput>>(),
            get<SimpleTool<GenerateDescriptionInput>>(),
            get<SimpleTool<DecomposeTaskInput>>(),
            get<SimpleTool<GenerateChecklistInput>>(),
            get<SimpleTool<PickTimeInput>>(),
            get<SimpleTool<ClusterTasksInput>>(),
            get<SimpleTool<ClusterNotesInput>>(),
            get<SimpleTool<ProjectReviewInput>>(),
            get<SimpleTool<WeeklyPlanInput>>(),
            get<SimpleTool<GetNoteInput>>(),
            get<SimpleTool<GetProjectInput>>(),
            get<SimpleTool<GetTaskInput>>(),
            get<SimpleTool<ListLinkedTasksInput>>(),
            get<SimpleTool<ListTasksInput>>(),
            get<SimpleTool<SearchTasksInput>>(),
        )
    }

    // AI Use Cases
    factory { RefineTaskUseCase(get()) }
    factory { SmartRewriteUseCase(get()) }
    factory { GenerateDescriptionUseCase(get()) }
    factory { DecomposeTaskUseCase(get()) }
    factory { GenerateChecklistUseCase(get()) }
    factory { PickTimeUseCase(get()) }
    factory { ClusterTasksUseCase(get()) }
    factory { ClusterNotesUseCase(get()) }
    factory { ImproveNoteUseCase(get()) }

    // AI Service — needs both the raw Koog PromptExecutor (for AIAgent builder)
    // and PromptExecutorPort (for streaming)
    single<com.singularity.todo.feature.ai.TextGenPort> {
        KoogAgentService(get(), get(), get(), get(), get())
    }

    // Platform-specific PromptExecutor (JvmPromptExecutorPort / StubPromptExecutorPort)
    single<PromptExecutorPort> { createKoogPromptExecutor() }

    // ─── GenUI ──────────────────────────────────────────────────────────

    // SurfaceController is stateful — singleton so all surfaces persist across screen rotations
    single { com.singularity.todo.feature.genui.surface.SurfaceController() }

    // A2uiParser is stateless — single instance for reuse
    single { com.singularity.todo.feature.genui.parser.A2uiParser() }

    // Platform-specific GenuiTransport (Jvm uses Koog; Android uses stub)
    factory<com.singularity.todo.feature.genui.transport.GenuiTransport> {
        com.singularity.todo.feature.genui.transport.KoogGenuiTransport(get())
    }

    // Orchestrator: Transport + Parser + Controller
    factory {
        com.singularity.todo.feature.genui.GenuiEngine(
            transport = get(),
            parser = get(),
            controller = get(),
        )
    }

    // ─── ViewModels ─────────────────────────────────────────────────────

    factory { SettingsViewModel(get(), get()) }

    factory { TasksViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    factory { ProjectsViewModel(get(), get(), get(), get(), get()) }

    factory { TagsViewModel(get(), get()) }

    factory { NotesViewModel(get(), get(), get()) }

    factory {
        AttachmentsViewModel(get(), com.singularity.todo.feature.tasks.UserId.anonymous)
    }

    factory { AuthViewModel(get()) }

    factory { BackupViewModel(get(), get()) }
}
