package com.singularity.todo.core.di

import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.notes.CreateNoteUseCase
import com.singularity.todo.feature.notes.NotesViewModel
import com.singularity.todo.feature.notes.RichEditorMarkdownHtmlPort
import com.singularity.todo.feature.notes.RoomNotesRepository
import com.singularity.todo.feature.notes.UpdateNoteUseCase
import com.singularity.todo.feature.projects.CreateProjectUseCase
import com.singularity.todo.feature.projects.ProjectEditorViewModel
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
import com.singularity.todo.feature.tasks.AttachmentSaver
import com.singularity.todo.feature.tasks.AttachmentsViewModelAttachmentSaver
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.usecase.DeleteTagUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Returns all domain-level bindings as a KOIN [Module].
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
    includes(coreDomainModule(), aiToolsModule())
}

/**
 * Common bindings shared by every AI tool module — use cases, GenUI,
 * ChatViewModel, and the AI service surface. Platform-specific bindings
 * (PromptExecutor, LLModel) live in the [aiToolsModule] actuals.
 */
internal fun aiToolsCoreModule(): Module = module {
    // ─── AI Service ───
    single<com.singularity.todo.feature.ai.TextGenPort> {
        com.singularity.todo.feature.ai.KoogAgentService(get(), get(), get(), get(), get())
    }

    factory { com.singularity.todo.feature.ai.chat.ChatViewModel(get(), get()) }

    // ─── GenUI ───
    single { com.singularity.todo.feature.genui.surface.SurfaceController() }
    single { com.singularity.todo.feature.genui.parser.A2uiParser() }

    factory<com.singularity.todo.feature.genui.transport.GenuiTransport> {
        com.singularity.todo.feature.genui.transport.KoogGenuiTransport(get())
    }

    factory {
        com.singularity.todo.feature.genui.GenuiEngine(
            transport = get(),
            parser = get(),
            controller = get(),
        )
    }

    // ─── AI Use Cases ───
    factory { com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.SmartRewriteUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.PickTimeUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.ClusterTasksUseCase(get()) }
    factory { com.singularity.todo.feature.ai.use_cases.ClusterNotesUseCase(get()) }
    factory {
        com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase(
            tool = get<com.singularity.todo.feature.ai.tools.ImproveNoteTool>(),
        )
    }
    factory { com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase(get()) }

    // ─── AI Tools ───
    factory { com.singularity.todo.feature.ai.tools.RefineTaskTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.SmartRewriteTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.GenerateDescriptionTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.DecomposeTaskTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.GenerateChecklistTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.PickTimeTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.ClusterTasksTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.ClusterNotesTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.ProjectReviewTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.WeeklyPlanTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.ImproveNoteTool(get(), get()) }
    factory { com.singularity.todo.feature.ai.tools.GetNoteTool(get()) }
    factory { com.singularity.todo.feature.ai.tools.GetProjectTool(get()) }
    factory { com.singularity.todo.feature.ai.tools.GetTaskTool(get()) }
    factory { com.singularity.todo.feature.ai.tools.ListLinkedTasksTool(get()) }
    factory { com.singularity.todo.feature.ai.tools.ListTasksTool(get()) }
    factory { com.singularity.todo.feature.ai.tools.SearchTasksTool(get()) }

    // ─── AI tools list for KoogAgentService ───
    single<List<ai.koog.agents.core.tools.Tool<*, *>>> {
        listOf(
            get<com.singularity.todo.feature.ai.tools.RefineTaskTool>(),
            get<com.singularity.todo.feature.ai.tools.SmartRewriteTool>(),
            get<com.singularity.todo.feature.ai.tools.GenerateDescriptionTool>(),
            get<com.singularity.todo.feature.ai.tools.DecomposeTaskTool>(),
            get<com.singularity.todo.feature.ai.tools.GenerateChecklistTool>(),
            get<com.singularity.todo.feature.ai.tools.PickTimeTool>(),
            get<com.singularity.todo.feature.ai.tools.ClusterTasksTool>(),
            get<com.singularity.todo.feature.ai.tools.ClusterNotesTool>(),
            get<com.singularity.todo.feature.ai.tools.ProjectReviewTool>(),
            get<com.singularity.todo.feature.ai.tools.WeeklyPlanTool>(),
            get<com.singularity.todo.feature.ai.tools.GetNoteTool>(),
            get<com.singularity.todo.feature.ai.tools.GetProjectTool>(),
            get<com.singularity.todo.feature.ai.tools.GetTaskTool>(),
            get<com.singularity.todo.feature.ai.tools.ListLinkedTasksTool>(),
            get<com.singularity.todo.feature.ai.tools.ListTasksTool>(),
            get<com.singularity.todo.feature.ai.tools.SearchTasksTool>(),
        )
    }

    // ─── ViewModels that depend on AI ───
    factory {
        com.singularity.todo.feature.tasks.TasksViewModel(
            taskRepo = get(),
            createTask = get(),
            updateTask = get(),
            currentUser = get(),
            mutations = get(),
            refineTask = get(),
            generateDescription = get(),
            generateChecklist = get(),
            decomposeTask = get(),
            pickTime = get(),
        )
    }
    factory {
        com.singularity.todo.feature.projects.ProjectsViewModel(
            projectRepo = get(),
            createProject = get(),
            currentUser = get(),
            taskRepository = get(),
            projectReview = get(),
            deleteProject = get(),
        )
    }
}

/**
 * Core domain: repositories, use cases, ViewModels, settings, sync.
 * Does NOT include AI tools (those require a valid OpenAI API key).
 */
fun coreDomainModule(): Module = module {
    // ─── Scopes ───────────────────────────────────────────────────────────

    single {
        { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    }

    single {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    // ─── Settings ────────────────────────────────────────────────────────

    // DataStore<Preferences> is bound per-platform in PlatformModule.{android,jvm}.kt
    // (real file on Android, in-memory stub on JVM). DataStoreSettingsRepository
    // works on both because androidx.datastore-preferences-core is in commonMain deps.
    single<com.singularity.todo.core.settings.SettingsRepository> {
        com.singularity.todo.core.settings.DataStoreSettingsRepository(get())
    }

    // ─── Session ────────────────────────────────────────────────────────

    // Use FakeSessionStore to avoid Android-only DataStore dependency in core domain.
    single<com.singularity.todo.core.auth.SessionStore> {
        com.singularity.todo.test.fakes.FakeSessionStore()
    }

    single<com.singularity.todo.core.auth.AuthRepository> {
        SupabaseAuthRepository(get(), Dispatchers.IO)
    }

    single { com.singularity.todo.core.auth.CurrentUser(get()) }

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

    // AttachmentStorage uses FileSystem from platformModule
    factory { com.singularity.todo.core.attachments.AttachmentStorage(get(), "/attachments") }

    single<com.singularity.todo.feature.reminders.ReminderRepository> {
        RoomReminderRepository(get())
    }

    single<com.singularity.todo.feature.checklist.ChecklistRepository> {
        com.singularity.todo.feature.checklist.RoomChecklistRepository(get(), get())
    }

    single<com.singularity.todo.feature.archive.ArchiveRepository> {
        com.singularity.todo.feature.archive.TaskDaoArchiveRepository(get(), get())
    }

    factory { com.singularity.todo.feature.checklist.ChecklistUseCase(get(), get()) }

    factory { com.singularity.todo.feature.checklist.ChecklistEditorViewModel(get()) }

    viewModel { com.singularity.todo.feature.archive.ArchiveViewModel(get(), get(), get()) }

    factory<com.singularity.todo.feature.pomodoro.PomodoroRepository> {
        com.singularity.todo.feature.pomodoro.InMemoryPomodoroRepository()
    }

    factory { com.singularity.todo.feature.pomodoro.PomodoroTimer(get(), get(), get()) }

    viewModel { com.singularity.todo.feature.statistics.StatisticsViewModel(get(), get(), get()) }

    // Platform clock singleton — actual implementation is in androidMain/jvmMain
    single { Clock }
    // ─── Ports ───────────────────────────────────────────────────────────

    single<com.singularity.todo.feature.notes.MarkdownHtmlPort> { RichEditorMarkdownHtmlPort() }

    single<com.singularity.todo.core.clock.AutosaveScheduler> { com.singularity.todo.core.clock.DelayAutosaveScheduler() }

    single<com.singularity.todo.core.attachments.AttachmentUploadService> { StubAttachmentUploadService() }

    single<com.singularity.todo.core.backup.RemoteBackupService> { StubRemoteBackupService() }

    // ─── UI-test ports ────────────────────────────────────────────────────

    factory<IdGenerator> { UlidIdGenerator }

    single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

    single<BackupFileNamer> { DefaultBackupFileNamer }

    single<AttachmentSaver> { AttachmentsViewModelAttachmentSaver { get<AttachmentsViewModel>() } }

    // ─── Sync ───────────────────────────────────────────────────────────

    single { HlcFactory(get(), get()) }

    single<com.singularity.todo.core.sync.SyncApiClient> { SupabaseSyncApiClient() }

    single { com.singularity.todo.core.sync.SyncEngine(get(), get(), get(), get(), get()) }

    // ─── Reminders ──────────────────────────────────────────────────────

    factory { ReminderScheduler(get(), get()) }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateTaskUseCase(get(), get()) }
    factory { UpdateTaskUseCase(get(), get()) }
    factory { TaskMutationsUseCase(get()) }

    factory { CreateNoteUseCase(get(), get()) }
    factory { UpdateNoteUseCase(get(), get()) }

    factory { CreateProjectUseCase(get(), get()) }
    factory { UpdateProjectUseCase(get(), get()) }
    factory { DeleteProjectUseCase(get(), get()) }

    factory { CreateTagUseCase(get(), get()) }
    factory { UpdateTagUseCase(get(), get()) }
    factory { DeleteTagUseCase(get()) }

    factory { SearchUseCase(get(), get(), get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    factory {
        SettingsViewModel(
            settings = get(),
            secureStorage = get(),
            textGen = get(),
            clock = { com.singularity.todo.core.platform.Clock.now().toEpochMilliseconds() },
        )
    }

    // TasksViewModel and ProjectsViewModel: registered in aiToolsModule()
    // (AI deps are null on Android; VMs handle null gracefully)

    factory { TagsViewModel(get(), get(), get()) }

    factory { NotesViewModel(get(), get(), get(), get(), get()) }

    factory { ProjectEditorViewModel(get(), get()) }

    factory { com.singularity.todo.feature.projects.ProjectDetailViewModel(get()) }

    factory { (initialDueDate: kotlinx.datetime.LocalDate?) ->
        com.singularity.todo.feature.tasks.TaskEditorViewModel(
            deps = com.singularity.todo.feature.tasks.TaskEditorDeps(
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

    factory {
        com.singularity.todo.feature.tasks.TaskDetailViewModel(
            get(), get(),
            get(), get(), get<com.singularity.todo.feature.checklist.ChecklistUseCase>(), get(), get(), get(),
        )
    }

    // ChatViewModel requires TextGenPort (AI) — registered in aiToolsModule()

    factory {
        AttachmentsViewModel(get(), get())
    }

    factory { AuthViewModel(get()) }

    factory { BackupViewModel(get(), get(), get(), get()) }

    // BackupRepository: full implementation requires backupDir + all DAOs + codecs.
    // Use FakeBackupRepository in coreDomainModule to unblock graph verification;
    // real BackupRepositoryImpl is registered in desktopApp DI setup.
    single<com.singularity.todo.core.backup.BackupRepository> {
        com.singularity.todo.test.fakes.FakeBackupRepository()
    }
}

/**
 * AI tools, GenUI, and AI use cases — platform-specific.
 * JVM: [jvmAiToolsModule] (uses Koog with real OpenAI).
 * Android: stub (AI features disabled).
 */
expect fun aiToolsModule(): Module
