package com.singularity.todo.core.di

import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.SupabaseSyncApiClient
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
import com.singularity.todo.feature.tasks.SetTagsUseCase
import com.singularity.todo.feature.tasks.TasksViewModel
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase
import com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase
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

    single<com.singularity.todo.feature.notes.NotesStore> {
        com.singularity.todo.feature.notes.RoomNotesStore(get(), get())
    }

    single<com.singularity.todo.core.attachments.AttachmentRepository> {
        AttachmentRepositoryImpl(get(), get(), get(), get())
    }

    single<com.singularity.todo.feature.reminders.ReminderRepository> {
        RoomReminderRepository(get())
    }

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
    factory { SetTagsUseCase(get()) }

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

    // Read-only tools (use repositories)
    factory { GetNoteTool(get()) }
    factory { GetProjectTool(get()) }
    factory { GetTaskTool(get()) }
    factory { ListLinkedTasksTool(get()) }
    factory { ListTasksTool(get()) }
    factory { SearchTasksTool(get()) }

    // All AI tools collected into a list for KoogAgentService
    single<List<Tool<*, *>>> {
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
            get<GetNoteTool>(),
            get<GetProjectTool>(),
            get<GetTaskTool>(),
            get<ListLinkedTasksTool>(),
            get<ListTasksTool>(),
            get<SearchTasksTool>(),
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

    // AI Service — needs both the raw Koog PromptExecutor (for AIAgent builder)
    // and PromptExecutorPort (for streaming)
    single<com.singularity.todo.feature.ai.TextGenPort> {
        KoogAgentService(get(), get(), get(), get(), get())
    }

    // Platform-specific PromptExecutor (JvmPromptExecutorPort / StubPromptExecutorPort)
    single<PromptExecutorPort> { createKoogPromptExecutor() }

    // ─── ViewModels ─────────────────────────────────────────────────────

    factory { SettingsViewModel(get(), get()) }

    factory { TasksViewModel(get(), get(), get(), get(), get()) }

    factory { ProjectsViewModel(get(), get(), get()) }

    factory { TagsViewModel(get(), get()) }

    factory { NotesViewModel(get(), get(), get(), get()) }

    factory {
        val userId = kotlinx.coroutines.runBlocking {
            com.singularity.todo.feature.tasks.UserId.fromString("anonymous")
        }
        AttachmentsViewModel(get(), userId)
    }

    factory { AuthViewModel(get()) }

    factory { BackupViewModel(get(), get()) }
}
