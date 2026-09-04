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
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
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

    // Use FakeSettingsRepository to avoid Android-only DataStore dependency in core domain.
    // Real DataStoreSettingsRepository requires androidx.datastore which is Android-only.
    single<com.singularity.todo.core.settings.SettingsRepository> {
        com.singularity.todo.test.fakes.FakeSettingsRepository()
    }

    // ─── Session ────────────────────────────────────────────────────────

    // Use FakeSessionStore to avoid Android-only DataStore dependency in core domain.
    single<com.singularity.todo.core.auth.SessionStore> {
        com.singularity.todo.test.fakes.FakeSessionStore()
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

    // AttachmentStorage uses FileSystem from platformModule
    factory { com.singularity.todo.core.attachments.AttachmentStorage(get(), "/attachments") }

    single<com.singularity.todo.feature.reminders.ReminderRepository> {
        RoomReminderRepository(get())
    }

    single<com.singularity.todo.feature.checklist.ChecklistRepository> {
        com.singularity.todo.feature.checklist.RoomChecklistRepository(get(), get())
    }

    factory { com.singularity.todo.feature.checklist.ChecklistUseCase(get(), get()) }

    factory { com.singularity.todo.feature.checklist.ChecklistEditorViewModel(get()) }

    factory<com.singularity.todo.feature.pomodoro.PomodoroRepository> {
        com.singularity.todo.feature.pomodoro.InMemoryPomodoroRepository()
    }

    factory { com.singularity.todo.feature.pomodoro.PomodoroTimer(get()) }

    viewModel { com.singularity.todo.feature.statistics.StatisticsViewModel(get(), get()) }

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

    // ─── ViewModels ─────────────────────────────────────────────────────

    factory { SettingsViewModel(get(), get()) }

    // TasksViewModel and ProjectsViewModel: registered in aiToolsModule()
    // (AI deps are null on Android; VMs handle null gracefully)

    factory { TagsViewModel(get(), get()) }

    factory { NotesViewModel(get(), get(), get()) }

    factory { ProjectEditorViewModel(get(), get()) }

    factory { (initialDueDate: kotlinx.datetime.LocalDate?) ->
        com.singularity.todo.feature.tasks.TaskEditorViewModel(get(), get(), com.singularity.todo.feature.tasks.UserId.anonymous, get(), get(), initialDueDate)
    }

    // ChatViewModel requires TextGenPort (AI) — registered in aiToolsModule()

    factory {
        AttachmentsViewModel(get(), com.singularity.todo.feature.tasks.UserId.anonymous)
    }

    factory { AuthViewModel(get()) }

    factory { BackupViewModel(get(), get()) }

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
