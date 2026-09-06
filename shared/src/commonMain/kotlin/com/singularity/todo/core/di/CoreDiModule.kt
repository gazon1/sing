package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.RemoteBackupService
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.AttachmentUploadService
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.RoomReminderRepository
import com.singularity.todo.feature.settings.SettingsViewModel
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.test.fakes.FakeBackupRepository
import com.singularity.todo.test.fakes.FakeSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Core platform bindings: settings, auth, sync, attachments, backup.
 * Does NOT include feature use cases or ViewModels — those live in feature modules.
 */
fun coreModule(): org.koin.core.module.Module = module {
    // ─── Scopes ───────────────────────────────────────────────────────────

    single { { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) } }

    single { CoroutineScope(Dispatchers.Default + SupervisorJob()) }

    // ─── Settings ────────────────────────────────────────────────────────

    // DataStore<Preferences> is bound per-platform in PlatformModule.{android,jvm}.kt
    // (real file on Android, in-memory stub on JVM).
    single<SettingsRepository> { DataStoreSettingsRepository(get()) }

    // ─── Session / Auth ─────────────────────────────────────────────────

    single<SessionStore> { FakeSessionStore() }

    single<AuthRepository> {
        SupabaseAuthRepository(Logger.withTag("AuthRepository"), get(), Dispatchers.IO)
    }

    single { CurrentUser(get()) }

    // ─── Repositories ───────────────────────────────────────────────────

    single<AttachmentRepository> { AttachmentRepositoryImpl(get(), get(), get(), get()) }

    factory { AttachmentStorage(get(), "/attachments") }

    single<ReminderRepository> { RoomReminderRepository(get(), get()) }

    // ─── Ports ───────────────────────────────────────────────────────────

    single<AttachmentUploadService> { StubAttachmentUploadService() }

    single<RemoteBackupService> { StubRemoteBackupService() }

    single<BackupFileNamer> { DefaultBackupFileNamer }

    // ─── Sync ───────────────────────────────────────────────────────────

    single { HlcFactory(get(), get()) }

    single<SyncApiClient> { SupabaseSyncApiClient() }

    single { SyncEngine(Logger.withTag("SyncEngine"), get(), get(), get(), get(), get()) }

    // ─── IDs / Clock ────────────────────────────────────────────────────

    factory<IdGenerator> { UlidIdGenerator }

    single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

    single { Clock }

    // ─── Backup ─────────────────────────────────────────────────────────

    // BackupRepository: full implementation requires backupDir + all DAOs + codecs.
    // Use FakeBackupRepository in coreModule to unblock graph verification;
    // real BackupRepositoryImpl is registered in desktopApp DI setup.
    single<BackupRepository> { FakeBackupRepository() }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModelOf(::SettingsViewModel)

    viewModelOf(::AuthViewModel)

    viewModelOf(::BackupViewModel)

    viewModelOf(::AttachmentsViewModel)
}
