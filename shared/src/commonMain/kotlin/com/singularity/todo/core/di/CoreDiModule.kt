package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.AttachmentUploadService
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupRepositoryImpl
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.RemoteBackupService
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.draft.DataStoreDraftStore
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.RoomReminderRepository
import com.singularity.todo.feature.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Core platform bindings: settings, auth, sync, attachments, backup.
 * Does NOT include feature use cases or ViewModels — those live in feature modules.
 */
fun coreModule(): org.koin.core.module.Module = module {
    // ─── Settings ────────────────────────────────────────────────────────

    // DataStore<Preferences> is bound per-platform in PlatformModule.{android,jvm}.kt
    // (real file on Android, in-memory stub on JVM).
    single<SettingsRepository> { DataStoreSettingsRepository(get()) }

    // ─── Drafts ──────────────────────────────────────────────────────────
    // DraftStore uses the same per-platform DataStore<Preferences> binding.
    // Drafts are not secrets — stored in regular DataStore, not SecureStoragePort.
    single<DraftStore> { DataStoreDraftStore(get(), Logger.withTag("DraftStore")) }

    // ─── Session / Auth ─────────────────────────────────────────────────

    single<SessionStore> { DataStoreSessionStore(get()) }

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

    single { SyncEngine(Logger.withTag("SyncEngine"), get(), get(), get(), get(), get(), get()) }

    // ─── IDs / Clock ────────────────────────────────────────────────────

    factory<IdGenerator> { UlidIdGenerator }

    single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

    single { Clock }

    // ─── Backup ─────────────────────────────────────────────────────────

    single { BackupExporter(get(), get(), get(), get(), get(), get(), get(), get()) }
    single {
        BackupImporter(
            Logger.withTag("BackupImporter"), get(), get(), get(), get(),
            get(), get(), get(), get(),
        )
    }
    single<RemoteBackupService> { StubRemoteBackupService() }
    single<BackupRepository> {
        BackupRepositoryImpl(
            exporter = get(),
            importer = get(),
            remoteService = get(),
            fs = get(),
            backupDir = get<String>(),
        )
    }
    // ─── Settings ───────────────────────────────────────────────────────

    // SettingsViewModel collects all SettingsContributor implementations via getAll<>.
    // Each contributor (Appearance, AI, …) is registered in its own feature module.
    factory {
        SettingsViewModel(
            contributors = getAll<com.singularity.todo.core.settings.SettingsContributor<*, *>>().toSet(),
            settings = get(),
        )
    }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModelOf(::AuthViewModel)

    viewModelOf(::BackupViewModel)

    viewModelOf(::AttachmentsViewModel)
}
