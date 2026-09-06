package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.log.LoggerHolder
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
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.projects.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.UpdateProjectUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.ProjectReviewUseCase
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.reminders.RoomReminderRepository
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.SearchViewModel
import com.singularity.todo.feature.settings.SettingsViewModel
import com.singularity.todo.feature.tags.CreateTagUseCase
import com.singularity.todo.feature.tags.TagsViewModel
import com.singularity.todo.feature.tags.TagsRepositoryImpl
import com.singularity.todo.feature.tags.UpdateTagUseCase
import com.singularity.todo.feature.tasks.AttachmentSaver
import com.singularity.todo.feature.tasks.AttachmentsViewModelAttachmentSaver
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.feature.tasks.TaskDetailViewModel
import com.singularity.todo.feature.tasks.TaskEditorDeps
import com.singularity.todo.feature.tasks.TaskEditorViewModel
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.usecase.TaskMutationsUseCase
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.usecase.DeleteTagUseCase
import com.singularity.todo.feature.archive.ArchiveViewModel
import com.singularity.todo.feature.statistics.StatisticsViewModel
import com.singularity.todo.feature.projects.ProjectDetailViewModel
import com.singularity.todo.feature.projects.ProjectsViewModel
import com.singularity.todo.feature.checklist.ChecklistEditorViewModel
import com.singularity.todo.feature.tasks.TasksViewModel
import com.singularity.todo.feature.ai.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Logging module — Kermit + Koin integration.
 */
fun coreLoggingModule(): Module = module {
    factory { Logger.withTag("App") }
    single { LoggerHolder(get()) }
}

/**
 * Returns all domain-level bindings as a KOIN [Module].
 */
fun domainModule(): Module = module {
    includes(
        tasksModule(),
        projectsModule(),
        notesModule(),
        tagsModule(),
        coreModule(),
        aiToolsModule(),
    )
}

/**
 * AI tools, GenUI, and AI use cases — platform-specific.
 * JVM: [jvmAiToolsModule] (uses Koog with real OpenAI).
 * Android: stub (AI features disabled).
 */
expect fun aiToolsModule(): Module
