package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.appearance.AppearanceContributor
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.attachments.AttachmentRepositoryImpl
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.StubAttachmentUploadService
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationRepository
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationRepositoryImpl
import com.singularity.todo.core.auth.AuthGateway
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.SecureSessionManager
import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.auth.SupabaseAuthGateway
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfigResolver
import com.singularity.todo.core.backup.ArchiveExporter
import com.singularity.todo.core.backup.ArchiveImporter
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupBucket
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupRepositoryImpl
import com.singularity.todo.core.backup.BackupCursorStore
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.DeviceKeyStore
import com.singularity.todo.core.backup.EncryptedArchive
import com.singularity.todo.core.backup.RemoteBackupService
import com.singularity.todo.core.backup.RemoteBackupServiceImpl
import com.singularity.todo.core.backup.SupabaseBackupBucket
import com.singularity.todo.core.backup.SecureStorageDeviceKeyStore
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.draft.DataStoreDraftStore
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UuidIdGenerator
import com.singularity.todo.core.network.createHttpClient
import com.singularity.todo.core.notifications.DataStoreNotificationsSettingsRepository
import com.singularity.todo.core.notifications.NotificationsSettingsRepository
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.schedule.DataStoreGreetingSettingsRepository
import com.singularity.todo.core.schedule.DataStoreWorkScheduleSettingsRepository
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.GreetingSettingsRepository
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.schedule.WorkScheduleSettingsRepository
import com.singularity.todo.core.security.SecureStorageAdapter
import com.singularity.todo.core.settings.DataStoreSettingsRepository
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsExporter
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.sync.DeviceIdProvider
import com.singularity.todo.core.sync.SeedPlanner
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncScopeProvider
import com.singularity.todo.core.tasks.TasksContributor
import com.singularity.todo.core.ui.onboarding.DataStoreOnboardingSettingsRepository
import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.core.wipe.ContentResetter
import com.singularity.todo.core.wipe.ProfileContentResetter
import com.singularity.todo.core.wipe.ProfileWipeDeps
import com.singularity.todo.feature.agenda.DefaultAgendaViewContributor
import com.singularity.todo.feature.ai.AiContributor
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.attachments.annotation.AttachmentAnnotationViewModel
import com.singularity.todo.feature.auth.AccountSwitcher
import com.singularity.todo.feature.auth.AuthDeepLink
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.auth.LocalAccountClaimer
import com.singularity.todo.feature.auth.OwnerRowIdResolver
import com.singularity.todo.feature.auth.OwnerCountsCalculator
import com.singularity.todo.feature.auth.OwnerScopedEraser
import com.singularity.todo.feature.auth.OwnerScopedReassigner
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.AlarmHandler
import com.singularity.todo.feature.reminders.ReminderDelivery
import com.singularity.todo.feature.reminders.data.ProjectRemindersRepositoryImpl
import com.singularity.todo.feature.reminders.data.ReminderRepositoryImpl
import com.singularity.todo.feature.reminders.domain.port.ProjectRemindersRepository
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Core platform bindings: settings, auth, sync, attachments, backup.
 * Does NOT include feature use cases or ViewModels — those live in feature modules.
 */
fun coreModule(): org.koin.core.module.Module =
    module {
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
        single<SettingsRepository> {
            DataStoreSettingsRepository(get(), get<ProfileAwareCurrentUser>().scopedUserId)
        }

        // The spotlight tour's "have I seen this yet" record, on the same DataStore and for
        // the same reason as `SettingsRepository` above — a second store would need its own
        // migration, its own behaviour when settings are cleared, and its own answer to
        // sign-out.
        //
        // Bound here because 26316f2b landed `SpotlightTourController` without it:
        // `:shared:jvmTest`, `:desktopApp:compileKotlin` and `:androidApp:compileDebugKotlin`
        // all failed with KOIN-D003 "Missing definition: OnboardingSettingsRepository", so
        // `origin/main` did not build between that commit and this one. The koin-compiler
        // plugin caught it at compile time, which is the only reason it was not found by
        // anyone reading the diff — the class was constructed in a `single` body, so nothing
        // about the binding was visible from `SpotlightTourController` alone.
        single<OnboardingSettingsRepository> { DataStoreOnboardingSettingsRepository(get()) }

        // The Notifications section of the same DataStore, bound on its own so a
        // feature can read ONE setting without taking the whole `SettingsRepository`.
        // `ProjectDetailViewModel` reads `reminderDefault` and nothing else, and
        // wiring that without a binding is what KOIN-D003
        // "Missing definition: NotificationsSettingsRepository" reported at
        // compile time — the class was constructed inside a `single` body, so the
        // missing edge was invisible from the ViewModel's own diff.
        //
        // A second instance, deliberately: `DataStoreNotificationsSettingsRepository`
        // holds no state of its own, it is a set of preferences keys over the
        // already-bound `DataStore<Preferences>`, so it cannot disagree with the
        // copy inside `SettingsRepository`. Binding it as a `factory` says exactly
        // that, and would break loudly if that ever stops being true.
        factory<NotificationsSettingsRepository> { DataStoreNotificationsSettingsRepository(get()) }
        factory<WorkScheduleSettingsRepository> { DataStoreWorkScheduleSettingsRepository(get()) }
        factory<GreetingSettingsRepository> { DataStoreGreetingSettingsRepository(get()) }

        // ─── Drafts ──────────────────────────────────────────────────────────
        // DraftStore uses the same per-platform DataStore<Preferences> binding.
        // Drafts are not secrets — stored in regular DataStore, not SecureStoragePort.
        single<DraftStore> { DataStoreDraftStore(get(), Logger.withTag("DraftStore")) }

        // ─── Profile wipe ─────────────────────────────────────────────────────
        // The E2E suite reaches this through the debug deep link, but the list of
        // tables it reaches is production behaviour: it lives in the library, and
        // `scripts/ci/check-profile-reset-inventory.py` keeps it, the inventory document and
        // the Room schema in step.
        single<ContentResetter> {
            ProfileContentResetter(
                deps = ProfileWipeDeps(
                    taskRepository = get(),
                    notesRepository = get(),
                    projectsRepository = get(),
                    savedAgendaViewsRepository = get(),
                    tagsRepository = get(),
                    tagGroupRepository = get(),
                    reminderDao = get(),
                    projectReminderDao = get(),
                    timeEntryDao = get(),
                    savedSearchDao = get(),
                    attachmentDao = get(),
                    annotationDao = get(),
                    attachmentStorage = get(),
                    proposalDao = get(),
                    proposalItemDao = get(),
                    calendarImportEventDao = get(),
                    calendarSyncTaskMapDao = get(),
                    googleEventShadowDao = get(),
                ),
                draftStore = get(),
                currentUser = get(),
            )
        }

        // ─── Session / Auth ─────────────────────────────────────────────────

        // The plain-text store is kept only for the device id and for a token a
        // pre-upgrade build left behind, which SecureSessionManager moves into the
        // keychain once and then erases. It has no token-writing API any more.
        // PlaintextTokenIsolationTest fails if one ever reappears.
        single { DataStoreSessionStore(get(), get()) }
        // The sync seam binds to the DataStore-backed store on purpose: the device
        // id is not a secret and lives in plain DataStore by design, and HlcFactory
        // must not depend on the token-bearing auth seam at all
        // (DeviceIdProvider is the whole contract it needs).
        single<DeviceIdProvider> { get<DataStoreSessionStore>() }
        // The credential store. The provider plugin is built with it, so the SDK's
        // session and the application's session are one session in one place: the
        // keychain, and not a second copy in the SDK's own settings store.
        single {
            SecureSessionManager(
                log = Logger.withTag("SecureSessionManager"),
                secure = get<SecureStorage>(),
                legacy = get(),
            )
        }

        // Which Supabase project to talk to. The resolver owns the precedence rule
        // (a stored value beats a build-time one) and the provider owns the client,
        // because the client cannot be built until a project is known and a fresh
        // install has none.
        single { SupabaseConfigResolver(idGenerator = get()) }
        single<SecureStorage> { SecureStorageAdapter(get()) }
        single {
            SupabaseClientProvider(
                resolver = get(),
                store = get<SecureStorage>(),
                sessionStore = get(),
            )
        }

        single<AuthGateway> {
            SupabaseAuthGateway(
                clients = get<SupabaseClientProvider>(),
                log = Logger.withTag("AuthGateway"),
            )
        }

        // AuthRepository: real Supabase-backed implementation.
        // For CI эмулятор testing without Supabase, use testAuthModule() which
        // overrides this binding via SingularityApp.extraKoinModules with allowOverride=true.
        // The scope is the `reportingScope` factory bound above rather than a
        // hand-rolled `SupervisorJob() + Dispatchers.Default`: choosing the dispatcher
        // is the platform layer's job (`NoDirectDispatchers` says so), and the factory
        // is where the failure handler — mandatory, per `createBackgroundScope` — is
        // attached. Same SupervisorJob, same dispatcher, one less place to forget.
        single<AuthRepository> {
            SupabaseAuthRepository(
                log = Logger.withTag("AuthRepository"),
                gateway = get(),
                localOwnerStore = get(),
                scope = get(),
            )
        }

        // The owner-scoped erase, split read / write so the caller has to resolve the ids
        // before it can delete anything. `AccountSwitcher` is the only caller, and it is the
        // reason these are bound at all: until a switch exists there is nobody to erase.
        single { OwnerRowIdResolver(database = get()) }
        single { OwnerScopedEraser(database = get(), log = { Logger.withTag("AccountSwitcher").i(it) }) }
        single {
            AccountSwitcher(
                authRepository = get(),
                syncRepository = get(),
                resolver = get(),
                eraser = get(),
                calculator = get(),
            )
        }

        // The other half of the erase: re-filing rows from one owner onto another. The
        // offline → account path, where the alternative to moving the rows is throwing
        // away work the user did on this device and nobody else has ever seen.
        single { OwnerScopedReassigner(database = get(), log = { Logger.withTag("LocalAccountClaimer").i(it) }) }
        // Single-pass counter for both reassign and erase options, used by the attach screen.
        single { OwnerCountsCalculator(database = get()) }
        single {
            LocalAccountClaimer(
                authRepository = get(),
                resolver = get(),
                reassigner = get(),
                eraser = get(),
                calculator = get(),
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

        // The directory is named here rather than resolved by type: `AttachmentStorage`
        // takes a plain `String`, and a plain `String` in the graph used to be a
        // directory that belonged to something else entirely. The attachments were
        // written into the log directory.
        factory { AttachmentStorage(fs = get(), dir = get(AttachmentDirQualifier)) }

        // Notes written against spans of a text attachment. Bound here rather than in a
        // feature module because the entity and its repository live in core/attachments — the
        // same place AttachmentRepository is bound.
        single<AttachmentAnnotationRepository> {
            AttachmentAnnotationRepositoryImpl(get(), get(), get())
        }

        // The attachment id is a runtime parameter: one panel per open attachment, so two
        // attachments in two nav entries must not share a ViewModel.
        viewModel { (attachmentId: AttachmentId) ->
            AttachmentAnnotationViewModel(
                // Typed rather than inferred: Koin resolves either, but an inferred `get()`
                // is invisible to `find-unwired-surfaces.py`, which is how a bound repository
                // looks unwired. See `AttachmentRepository` in the same module for the
                // constructor-injected form of the same problem.
                repository = get<AttachmentAnnotationRepository>(),
                attachmentId = attachmentId,
                crashReporter = get(),
            )
        }

        single<ReminderRepository> { ReminderRepositoryImpl(get(), get(), get()) }
        // Both platforms fire reminders through this one object. It lives in common code
        // precisely so the Android BroadcastReceiver and the Desktop `fire-reminder` launcher
        // cannot drift on the four steps that decide what the user actually sees.
        single { ReminderDelivery(get(), get(), get()) }
        // Common code even though only Android raises alarms: the decisions live here so they
        // can be tested without a `BroadcastReceiver`, and the receiver is left with the
        // `Intent` parsing and `goAsync()` that are genuinely Android's.
        single { AlarmHandler(get(), get(), get(), get(), get()) }
        single<ProjectRemindersRepository> { ProjectRemindersRepositoryImpl(get(), get(), get()) }

        // ─── Ports ───────────────────────────────────────────────────────────

        singleOf(::StubAttachmentUploadService)

        single { DefaultBackupFileNamer() }

        // The remote half of the backup feature.
        //
        // A Supabase Storage bucket, not a stub: the previous implementation
        // returned success without uploading anything, so the button reported
        // "Backup pushed" and nothing was pushed. The real one fails honestly
        // when there is no project configured, which is the answer the user
        // needs.
        //
        // The bucket's own calls are behind a port, so the service that decides
        // where an archive goes and what a failure means can be tested without a
        // network.
        single<BackupBucket> { SupabaseBackupBucket(get()) }

        // The archive's envelope. The cryptography behind it is a port the platform
        // provides — `ArchiveCrypto` — and the envelope is the project's own: the
        // header, the salt, the nonce and the version. The server never sees the
        // password, so it can never open what it stores.
        single { EncryptedArchive(get()) }
        single<RemoteBackupService> { RemoteBackupServiceImpl(get(), get(), get(), get()) }

        // The key a device seals its archives with when no person is there.
        //
        // It is generated on first use and kept in the platform keystore, and it
        // is wrapped under the person's password once — so a scheduled run can
        // seal an archive without a person, and the person can still open it.
        // The password itself is not kept: storing it would remove the property
        // the encryption exists for.
        single<DeviceKeyStore> { SecureStorageDeviceKeyStore(get(), get(), get()) }

        // ─── IDs / Clock ────────────────────────────────────────────────────

        single<IdGenerator> { UuidIdGenerator }

        single<TimeZoneProvider> { com.singularity.todo.core.platform.systemTimeZone }

        // NoDirectClockSystemRule exemption: the singleton binding itself is the intentional
        // call site. All production code must inject Clock; only this binding uses Clock.System.
        single<Clock> { Clock.System }

        // ─── Observability ─────────────────────────────────────────────────

        // Analytics — off by default (GDPR). NoopAnalytics is a safe all-no-op.
        // TODO: when a real SDK is connected, replace with RealAnalytics(binding).
        single<com.singularity.todo.core.analytics.Analytics> {
            com.singularity.todo.core.analytics
                .NoopAnalytics()
        }

        // ─── Billing ──────────────────────────────────────────────────────

        // No-op billing provider. Real implementation (Google Play, RevenueCat, Supabase)
        // will replace this in a follow-up ADR.
        single<com.singularity.todo.core.billing.SubscriptionProvider> {
            com.singularity.todo.core.billing
                .NoopSubscriptionProvider()
        }

        // ─── Backup ─────────────────────────────────────────────────────────

        // Where the backup keeps its own bookkeeping: the cursor, the chain and
        // the fingerprints of the last export's rows.
        //
        // It lives in the backup directory, and it is device-local: the push
        // names the archive it uploads and the changes that follow it, and the
        // bookkeeping is not among them.
        single { BackupCursorStore(fs = get(), dir = get(BackupDirQualifier)) }

        // The repository talks to the archive through the two ports, not to the
        // concrete classes: the ports are what a test can replace.
        single {
            BackupExporter(
                taskDao = get(),
                noteDao = get(),
                projectDao = get(),
                tagDao = get(),
                reminderDao = get(),
                projectReminderDao = get(),
                checklistDao = get(),
                tagGroupDao = get(),
                projectTagGroupDao = get(),
                savedSearchDao = get(),
                timeEntryDao = get(),
                profileDao = get(),
                attachmentDao = get(),
                annotationDao = get(),
                agendaViewDao = get(),
                codec = get(),
                clock = get(),
                fs = get(),
                cursors = get(),
                backupDir = get(BackupDirQualifier),
            )
        }
        single<ArchiveExporter> { get<BackupExporter>() }
        single<ArchiveImporter> {
            BackupImporter(
                Logger.withTag("BackupImporter"),
                get(), // taskDao
                get(), // noteDao
                get(), // projectDao
                get(), // tagDao
                get(), // reminderDao
                get(), // projectReminderDao
                get(), // checklistDao
                get(), // tagGroupDao
                get(), // projectTagGroupDao
                get(), // savedSearchDao
                get(), // timeEntryDao
                get(), // profileDao
                get(), // agendaViewDao
                get(), // attachmentDao
                get(), // annotationDao
                get(), // attachmentStorage
                get(), // codec
                get(), // clock
                createFileSource = get(),
                tasksSettings = get(),
                database = get(),
            )
        }
        single<BackupRepository> {
            BackupRepositoryImpl(
                exporter = get(),
                importer = get(),
                remoteService = get(),
                fs = get(),
                backupDir = get(BackupDirQualifier),
                currentUser = get(),
                clock = get(),
                deviceKeyStore = get(),
                cursors = get(),
                ioDispatcher = get(),
            )
        }
        // ─── Settings ────────────────────────────────────────────────────────

        // SettingsViewModel uses marker interface lookups — each contributor is
        // registered individually in its own feature module and injected here via
        // getOptionalOrNull.
        //
        // `getOptionalOrNull`, not `getOrNull`: a contributor that *exists* but
        // cannot be created is a broken binding, not an absent feature, and it
        // used to take the settings screen down in composition. A missing
        // contributor is the normal case and is not reported.
        viewModel {
            SettingsViewModel(
                // No `scope =` here on purpose — see SyncViewModel above. The ViewModel derives
                // its own from crashReporter; this used to override that with a graph-supplied
                // one, which is the divergence NoDivergentScopeAndReporter exists to catch.
                appearanceContributor = getOptionalOrNull<AppearanceContributor> {
                    reportBrokenContributor(it)
                },
                notificationsContributor = getOptionalOrNull<NotificationsContributor> {
                    reportBrokenContributor(it)
                },
                workScheduleContributor = getOptionalOrNull<WorkScheduleContributor> {
                    reportBrokenContributor(it)
                },
                greetingContributor = getOptionalOrNull<GreetingContributor> {
                    reportBrokenContributor(it)
                },
                aiContributor = getOptionalOrNull<AiContributor> { reportBrokenContributor(it) },
                defaultAgendaViewContributor = getOptionalOrNull<DefaultAgendaViewContributor> {
                    reportBrokenContributor(it)
                },
                tasksContributor = getOptionalOrNull<TasksContributor> { reportBrokenContributor(it) },
                savedAgendaViewsRepo = get(),
                fileRevealer = get(),
                logBundleExporter = get(),
                fileSharePort = get(),
                crashReporter = get(),
            )
        }

        // ─── ViewModels ─────────────────────────────────────────────────────

        // The magic-link is owned by the auth feature: it declares the URI template
        // and the mapping to the confirmation screen. The registry that collects the
        // links lives in `feature/nav/deeplink`.
        single { AuthDeepLink() }

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
                // The offline → account path, passed as behaviour rather than as the
                // claimer itself for the reason `onFirstSignIn` is a lambda: the ViewModel
                // should not be able to reach the database collaborators by accident.
                // Returns a Flow<ClaimStep> so intermediate states (SignOutFailed etc.)
                // are available to the UI directly.
                claimAccount = { email, password, disposition ->
                    get<LocalAccountClaimer>().claim(email, password, disposition)
                },
                // What the user stands to lose, counted where the rows live.
                summariseLocalData = { owner -> get<LocalAccountClaimer>().summarise(owner) },
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
                fileSharePort = get(),
                crashReporter = get(),
            )
        }

        viewModel { AttachmentsViewModel(repository = get(), crashReporter = get()) }
    }

/**
 * Reports a settings contributor that exists but cannot be created.
 *
 * A contributor is optional by design: the feature that owns it may not be in
 * the graph, and the settings screen then shows one section less. A contributor
 * that *is* in the graph and cannot be created is a defect — the same shape that
 * took the note editor down — so it is reported instead of swallowed.
 */
private fun Scope.reportBrokenContributor(failure: Throwable) {
    getOrNull<Logger>()?.w(
        "A settings contributor exists but cannot be created — the settings screen continues without it",
        failure,
    )
}
