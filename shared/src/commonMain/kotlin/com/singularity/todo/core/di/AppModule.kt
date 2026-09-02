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
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupRepositoryImpl
import com.singularity.todo.core.backup.RemoteBackupService
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.sync.SyncOutboxDao
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
import com.singularity.todo.feature.backup.BackupViewModel
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
 *
 * The database parameter accepts either:
 * - AppDatabase (Android — Room-generated implementation)
 * - JvmDatabase (desktop — JDBC-backed implementation)
 * Both have identical DAO method signatures.
 */
@Suppress("UNCHECKED_CAST")
fun sharedModule(
    database: Any,
    settingsRepository: SettingsRepository,
    supabaseConfig: SupabaseConfig?,
    attachmentsDir: String,
    backupDir: String,
    fs: FileSystem,
    backupCodec: BackupCodec
): Module {
    // Resolve DAO methods via reflection (both AppDatabase and JvmDatabase have identical signatures)
    val taskDao: TaskDao = database.javaClass.getMethod("taskDao").invoke(database) as TaskDao
    val noteDao: NoteDao = database.javaClass.getMethod("noteDao").invoke(database) as NoteDao
    val projectDao: ProjectDao = database.javaClass.getMethod("projectDao").invoke(database) as ProjectDao
    val tagDao: TagDao = database.javaClass.getMethod("tagDao").invoke(database) as TagDao
    val syncOutboxDao: SyncOutboxDao = database.javaClass.getMethod("syncOutboxDao").invoke(database) as SyncOutboxDao
    val attachmentDao: AttachmentDao = database.javaClass.getMethod("attachmentDao").invoke(database) as AttachmentDao

    return module {
        // Database
        single { taskDao }
        single { noteDao }
        single { projectDao }
        single { tagDao }
        single { syncOutboxDao }
        single { attachmentDao }

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
        single<SyncApiClient> { SupabaseSyncApiClient() }

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

        // Backup
        single<BackupCodec> { backupCodec }
        single { BackupExporter(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { BackupImporter(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<RemoteBackupService> { StubRemoteBackupService() }
        single<BackupRepository> { BackupRepositoryImpl(get(), get(), get(), get(), backupDir, get()) }
        factory { BackupViewModel(get(), get()) }
    }
}
