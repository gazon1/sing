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
import com.singularity.todo.core.auth.AuthGateway
import com.singularity.todo.core.auth.SecureSessionStore
import com.singularity.todo.core.auth.SupabaseAuthGateway
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfigResolver
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.security.SecureStorageAdapter
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupRepositoryImpl
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.StubRemoteBackupService
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.draft.DataStoreDraftStore
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UlidIdGenerator
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.network.createHttpClient
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsExporter
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.sync.DataStoreSyncPrefs
import com.singularity.todo.core.sync.PatchRetryPolicy
import com.singularity.todo.core.sync.RoomSyncStateRepository
import kotlinx.coroutines.flow.first

import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncScopeProvider
import com.singularity.todo.core.sync.SyncStateRepository
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.RemoteConfigRepository
import com.singularity.todo.core.sync.RemoteConfigRepositoryImpl
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.sync.PostgrestSyncRpc
import com.singularity.todo.core.sync.SeedPlanner
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncRpc
import com.singularity.todo.core.sync.SyncBootstrapper
import com.singularity.todo.core.sync.SyncCoordinator
import com.singularity.todo.core.sync.SyncPatchBuilder
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
    // ─── HTTP ───────────────────────────────────────────────────────────

    // One client for the whole graph, so two features cannot quietly disagree about
    // timeouts. Bound here rather than in a feature module because it is a process-wide
    // resource: Ktor clients hold a connection pool, and a second one would mean a second
    // pool. Consumers must not close it — the graph owns its lifetime.
    single { createHttpClient() }

    // ─── Coroutine Scope ────────────────────────────────────────────────

    // Background scope для долгоживущих компонентов (репозитории, движки синхронизации).
    // factory, а не single — каждый потребитель получает свой экземпляр,
    // который закрывается вместе с владельцем.
    // The failure handler is composed from the injected CrashReportingPort, not read from a
    // process-wide target: a long-lived component's background failures go wherever this
    // graph's reporter sends them, and that is visible in this file.
    factory {
        reportingScope(get())
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

    // The plain-text store is kept only for the device id and for a token a
    // pre-upgrade build left behind, which SecureSessionStore moves into the
    // keychain once and then erases. It is deliberately not bound as the
    // SessionStore — PlaintextTokenIsolationTest fails if one ever is.
    single { DataStoreSessionStore(get(), get()) }
    single {
        SecureSessionStore(
            log = Logger.withTag("SecureSessionStore"),
            secure = get<SecureStorage>(),
            legacy = get(),
        )
    }
    single<SessionStore> { get<SecureSessionStore>() }

    // Which Supabase project to talk to. The resolver owns the precedence rule
    // (a stored value beats a build-time one) and the provider owns the client,
    // because the client cannot be built until a project is known and a fresh
    // install has none.
    single { SupabaseConfigResolver(idGenerator = get()) }
    single<SecureStorage> { SecureStorageAdapter(get()) }
    single { SupabaseClientProvider(resolver = get(), store = get<SecureStorage>()) }

    single<AuthGateway> {
        SupabaseAuthGateway(
            clients = get<SupabaseClientProvider>(),
            log = Logger.withTag("AuthGateway"),
        )
    }

    single<AuthRepository> {
        SupabaseAuthRepository(
            log = Logger.withTag("AuthRepository"),
            gateway = get<AuthGateway>(),
            sessionStore = get<SecureSessionStore>(),
            scope = get(),
        )
    }

    single { CurrentUser(get(), createBackgroundScope(crashReportingFailureHandler(get()))) }

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

    // The transport is built over the RPC port rather than over the vendor client,
    // so the parsing — where a 64-bit log position is read, and where a malformed
    // response has to become an AppError — is testable without a network.
    single<SyncRpc> { PostgrestSyncRpc(get()) }
    single<SyncApiClient> { SupabaseSyncApiClient(get<SyncRpc>()) }

    // SyncPrefs: DataStore-backed (not in-memory).
    single<SyncPrefs> { DataStoreSyncPrefs(get(), get(), clock = get()) }

    // Patches are built here rather than inside SyncEngine, so the diff has one
    // owner and can be tested without a push path.
    single { SyncPatchBuilder(shadowDao = get(), hlcFactory = get(), idGenerator = get()) }

    // SyncEngine is internal — feature modules must use SyncRepository.
    // Takes the per-scope state repository (for LSN tracking), the scope provider
    // (whose (owner, profile) the cycle applies to), the shadow store (the base a
    // diff is taken against) and SyncWorkScheduler (for auth-session init).
    single {
        SyncEngine(
            log = Logger.withTag("SyncEngine"),
            api = get(),
            authRepository = get(),
            outboxDao = get(),
            deadLetterDao = get(),
            idGenerator = get(),
            stateRepository = get(),
            scopeProvider = get(),
            shadowDao = get(),
            patchBuilder = get(),
            scheduler = get(),
            retryPolicy = get(),
            clock = get(),
            scope = get(),
            crashReporter = get(),
        )
    }

    // Backoff policy for rejected patches. One instance so the outbox, the push path
    // and the settings screen all agree on what "too many attempts" means.
    single { PatchRetryPolicy() }

    // Per-scope sync state, in the database rather than in flat preferences — the
    // download cursor is meaningless without knowing whose cursor it is. The legacy
    // DataStore values are adopted once, by the first scope to initialise.
    single<SyncStateRepository> {
        RoomSyncStateRepository(dao = get(), legacyPrefs = get(), idGenerator = get())
    }

    // Single owner of the sync cycle. Every trigger — periodic, user-initiated,
    // WorkManager — requests through it, so two cycles cannot overlap.
    //
    // The engine is resolved to a local rather than looked up inside the lambda:
    // a `get()` nested in a lambda argument is outside what the Koin compiler
    // plugin can verify, and an unverifiable graph is exactly the state this
    // project refuses to build with.
    single {
        val engine = get<SyncEngine>()
        SyncCoordinator(runCycle = { engine.syncOnce() }, scope = get())
    }

    // SyncRunner is internal.
    single {
        SyncRunner(
            engine = get(),
            coordinator = get(),
            periodicTrigger = get(),
            authRepository = get(),
            stateRepository = get(),
            scopeProvider = get(),
            scope = get(),
        )
    }

    // Public facade.
    single<SyncRepository> {
        SyncRepositoryImpl(
            engine = get(),
            runner = get(),
            coordinator = get(),
            api = get(),
            authRepository = get(),
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

    // SeedPlanner: the one-time upload of data that existed before sign-in. It
    // shares the bootstrapper's reach into the feature repositories for the same
    // reason — a bootstrapper is the one place that knows every entity type.
    single {
        SeedPlanner(
            stateRepository = get(),
            enqueue = { entity -> get<SyncEngine>().enqueue(entity) },
            taskRepo = get(),
            noteRepo = get(),
            projectRepo = get(),
            tagRepo = get(),
            tagGroupRepo = get(),
        )
    }

    // ─── Sync ViewModel ─────────────────────────────────────────────────

    // Named, not positional: every parameter below is a different type, and a positional
    // call silently reorders them the moment one moves.
    //
    // No `scope =` here on purpose. The ViewModel derives its own from crashReporter, so the
    // two failure paths cannot end up at different destinations. Passing both independently
    // is what NoDivergentScopeAndReporter reports.
    viewModel {
        SyncViewModel(
            repository = get(),
            stateRepository = get(),
            scopeProvider = get(),
            crashReporter = get(),
        )
    }

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
            get(), // taskDao
            get(), // noteDao
            get(), // projectDao
            get(), // tagDao
            get(), // agendaViewDao
            get(), // attachmentStorage
            get(), // codec
            get(), // clock
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
            // No `scope =` here on purpose — see SyncViewModel above. The ViewModel derives
            // its own from crashReporter; this used to override that with a graph-supplied
            // one, which is the divergence NoDivergentScopeAndReporter exists to catch.
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

    viewModel {
        AuthViewModel(
            authRepository = get(),
            clients = get<SupabaseClientProvider>(),
            // The seed is a one-shot upload of whatever the device held before the
            // sign-in. It is a lambda rather than a dependency so the auth feature
            // does not reach into sync.
            onFirstSignIn = {
                // Named `activeScope` because `scope` is the Koin receiver in this
                // DSL, and shadowing it makes every `get` below resolve against a
                // SyncScope.
                val activeScope: SyncScope? = get<SyncScopeProvider>().current.first()
                if (activeScope != null) get<SeedPlanner>().plan(activeScope)
            },
            crashReporter = get(),
        )
    }

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
            crashReporter = get(),
        )
    }

    viewModel { AttachmentsViewModel(repository = get(), crashReporter = get()) }
}
