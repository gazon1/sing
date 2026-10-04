package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.appearance.AppearanceContributor
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupRepositoryImpl
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.draft.DataStoreDraftStore
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsExporter
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.sync.DataStoreSyncPrefs
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.RemoteConfigRepository
import com.singularity.todo.core.sync.RemoteConfigRepositoryImpl
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncBootstrapper
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SyncPrefs
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.SyncRepositoryImpl
import com.singularity.todo.core.sync.SyncRunner
import com.singularity.todo.feature.agenda.DefaultAgendaViewContributor
import com.singularity.todo.feature.ai.AiContributor
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.reminders.data.ProjectRemindersRepositoryImpl
import com.singularity.todo.feature.reminders.data.ReminderRepositoryImpl
import com.singularity.todo.feature.reminders.domain.port.ProjectRemindersRepository
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.settings.SettingsViewModel
import com.singularity.todo.feature.sync.presentation.SyncViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Core platform bindings: settings, auth, sync, attachments, backup.
 * Does NOT include feature use cases or ViewModels — those live in feature modules.
 */
fun coreModule(): org.koin.core.module.Module = module {
    // ─── Coroutine Scope ────────────────────────────────────────────────

    // Background scope для долгоживущих компонентов (репозитории, движки синхронизации).
    // factory, а не single — каждый потребитель получает свой экземпляр,
    // который закрывается вместе с владельцем.
    factory {
        AutoCloseableCoroutineScope(createBackgroundScope().coroutineContext)
    }

    // ─── Settings ────────────────────────────────────────────────────────

    // DataStore<Preferences> is bound per-platform in PlatformModule.{android,jvm}.kt
    // (real file on Android, in-memory stub on JVM).
    single<SettingsRepository> { DataStoreSettingsRepository(get()) }

    // ─── Drafts ──────────────────────────────────────────────────────────
    // DraftStore uses the same per-platform DataStore<Preferences> binding.
    // Drafts are not secrets — stored in regular DataStore, not SecureStoragePort.
    single<DraftStore> { DataStoreDraftStore(get(), Logger.withTag("DraftStore")) }

    // ─── Session / Auth ─────────────────────────────────────────────────

    single<SessionStore> { DataStoreSessionStore(get(), get()) }

    single<AuthRepository> {
        SupabaseAuthRepository(
            Logger.withTag("AuthRepository"),
            get(),
            get(),
        )
    }

    single { CurrentUser(get(), createBackgroundScope()) }

    // ─── Repositories ───────────────────────────────────────────────────

    single<AttachmentRepository> {
        AttachmentRepositoryImpl(
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    factoryOf(::AttachmentStorage)

    single<ReminderRepository> { ReminderRepositoryImpl(get(), get(), get()) }
    single<ProjectRemindersRepository> { ProjectRemindersRepositoryImpl(get(), get(), get()) }

    // ─── Ports ───────────────────────────────────────────────────────────

    singleOf(::StubAttachmentUploadService)

    single { DefaultBackupFileNamer() }

    singleOf(::StubRemoteBackupService)

    // ─── Sync ───────────────────────────────────────────────────────────

    // HlcFactory is internal; SyncEngine depends on it.
    single {
        HlcFactory(
            get(),
            get(),
            get(),
        )
    }

    single<SyncApiClient> { SupabaseSyncApiClient() }

    // SyncPrefs: DataStore-backed (not in-memory).
    single<SyncPrefs> { DataStoreSyncPrefs(get(), get()) }

    // SyncEngine is internal — feature modules must use SyncRepository.
    // Takes both SyncPrefs (for LSN tracking) and SyncWorkScheduler (for auth-session init).
    single {
        SyncEngine(
            Logger.withTag("SyncEngine"),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    // SyncRunner is internal.
    single {
        SyncRunner(
            engine = get(),
            scheduler = get(),
            authRepository = get(),
            prefs = get(),
            scope = get(),
        )
    }

    // Public facade.
    single<SyncRepository> {
        SyncRepositoryImpl(
            engine = get(),
            runner = get(),
            prefs = get(),
            api = get(),
            authRepository = get(),
            scope = get(),
        )
    }

    // RemoteConfigRepository (Supabase endpoint credentials — kept in core/sync, not renamed).
    single<RemoteConfigRepository> { RemoteConfigRepositoryImpl(get(), get()) }

    // RemoteConfigPort: Room + network-backed runtime config snapshot.
    // Consumes SyncApiClient (stub in MR-2) and Clock.
    single<RemoteConfigPort> { com.singularity.todo.core.config.RemoteConfigCacheRepositoryImpl(get(), get()) }

    // SyncBootstrapper: registers pull handlers for all DocTypes.
    // Must be instantiated AFTER all feature repositories (Task, Note, Project, Tag, TagGroup).
    // The init {} block performs the registration.
    single {
        SyncBootstrapper(
            engine = get(),
            taskRepo = get(),
            noteRepo = get(),
            projectRepo = get(),
            tagRepo = get(),
            tagGroupRepo = get(),
            timeTrackingRepo = get(),
        )
    }

    // AutoSync is NOT in DI — callers construct it with their own CoroutineScope.
    // Example: val autoSync = AutoSync(get(), get(), viewModelScope)

    // ─── Sync ViewModel ─────────────────────────────────────────────────

    viewModel { SyncViewModel(get(), get(), get()) }

    // ─── IDs / Clock ────────────────────────────────────────────────────

    single<IdGenerator> { UlidIdGenerator }

    single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

    // NoDirectClockSystemRule exemption: the singleton binding itself is the intentional
    // call site. All production code must inject Clock; only this binding uses Clock.System.
    single<Clock> { Clock.System }

    // ─── Observability ─────────────────────────────────────────────────

    // Analytics — off by default (GDPR). NoopAnalytics is a safe all-no-op.
    // TODO: when a real SDK is connected, replace with RealAnalytics(binding).
    single<com.singularity.todo.core.analytics.Analytics> {
        com.singularity.todo.core.analytics.NoopAnalytics()
    }

    // ─── Billing ──────────────────────────────────────────────────────

    // No-op billing provider. Real implementation (Google Play, RevenueCat, Supabase)
    // will replace this in a follow-up ADR.
    single<com.singularity.todo.core.billing.SubscriptionProvider> {
        com.singularity.todo.core.billing.NoopSubscriptionProvider()
    }

    // ─── Backup ─────────────────────────────────────────────────────────

    singleOf(::BackupExporter)
    single {
        BackupImporter(
            Logger.withTag("BackupImporter"),
            get(),        // taskDao
            get(),        // noteDao
            get(),        // projectDao
            get(),        // tagDao
            get(),        // agendaViewDao
            get(),        // attachmentStorage
            get(),        // codec
            get(),        // clock
            createFileSource = get(),
        )
    }
    single<BackupRepository> {
        BackupRepositoryImpl(
            exporter = get(),
            importer = get(),
            remoteService = get(),
            fs = get(),
            backupDir = get<String>(),
            currentUser = get(),
        )
    }
    // ─── Settings ────────────────────────────────────────────────────────

    // SettingsViewModel uses marker interface lookups — each contributor is
    // registered individually in its own feature module and injected here via getOrNull.
    viewModel {
        SettingsViewModel(
            scope = get(),
            appearanceContributor = getOrNull<AppearanceContributor>(),
            notificationsContributor = getOrNull<NotificationsContributor>(),
            workScheduleContributor = getOrNull<WorkScheduleContributor>(),
            greetingContributor = getOrNull<GreetingContributor>(),
            aiContributor = getOrNull<AiContributor>(),
            defaultAgendaViewContributor = getOrNull<DefaultAgendaViewContributor>(),
            savedAgendaViewsRepo = get(),
            fileRevealer = get(),
            logBundleExporter = get(),
            fileSharePort = get(),
            crashReporter = get(),
        )
    }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { AuthViewModel(authRepository = get()) }

    // Settings snapshot exporter / importer (registered as single — stateless, no per-injection state)
    single { SettingsExporter(getAll<SettingsContributor<*, *>>().toSet()) }
    single { SettingsImporter(getAll<SettingsContributor<*, *>>().toSet()) }

    viewModel {
        BackupViewModel(
            repository = get(),
            authRepository = get(),
            backupFileNamer = get(),
            clock = get(),
            settingsExporter = get(),
            settingsImporter = get(),
            fileSourceFactory = get(),
        )
    }

    viewModel { AttachmentsViewModel(repository = get(), crashReporter = get()) }
}
