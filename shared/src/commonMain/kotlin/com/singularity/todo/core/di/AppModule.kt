package com.singularity.todo.core.di

import com.singularity.todo.core.attachments.AttachmentConverters
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.network.createSupabaseClient
import com.singularity.todo.core.network.createHttpClient
import com.singularity.todo.core.network.SupabaseConfig
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.TasksViewModel
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext

/**
 * Main shared DI module - wires all core components.
 * Platform-specific setup (database, settings, etc.) is done in platform entry points.
 */
fun sharedModule(
    database: AppDatabase,
    settingsRepository: SettingsRepository,
    supabaseConfig: SupabaseConfig?,
    attachmentsDir: String,
    fs: FileSystem
): Module = module {
    // Database
    single<AppDatabase> { database }
    single { database.taskDao() }
    single { database.noteDao() }
    single { database.projectDao() }
    single { database.tagDao() }
    single { database.syncOutboxDao() }
    single { database.attachmentDao() }

    // Settings
    single<SettingsRepository> { settingsRepository }

    // Network (optional - may be null if Supabase not configured)
    if (supabaseConfig != null) {
        single { createHttpClient() }
        single { createSupabaseClient(supabaseConfig, get()) }
    }

    // Auth
    single { SessionStore(get()) }
    single<AuthRepository> {
        SupabaseAuthRepository(
            sessionStore = get(),
            sessionCoroutineContext = Dispatchers.Default
        )
    }

    // Sync
    single { HlcFactory(get(), get()) }
    single<SyncApiClient> { SupabaseSyncApiClient() }  // Stub - replace with real when SDK integrated

    // Sync engine scope
    single(named("sync")) { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    single {
        SyncEngine(
            api = get(),
            authRepository = get(),
            outboxDao = get(),
            hlcFactory = get(),
            syncCoroutineScope = get(named("sync"))
        )
    }

    // Repositories
    single<TaskRepository> { TaskRepositoryImpl(get(), get()) }

    // Attachments
    single<StubAttachmentUploadService> { StubAttachmentUploadService() }
    single<FileSystem> { fs }
    single { AttachmentStorage(get(), attachmentsDir) }
    single<AttachmentRepository> { AttachmentRepositoryImpl(get(), get(), get(), get()) }

    // ViewModels
    factory { TasksViewModel(get(), get(), get(), get()) }
    factory { AuthViewModel(get()) }
    factory { AttachmentsViewModel(get(), get()) }
}
