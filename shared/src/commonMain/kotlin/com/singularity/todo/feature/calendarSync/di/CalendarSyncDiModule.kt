package com.singularity.todo.feature.calendarSync.di

import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.feature.calendarSync.auth.GoogleAuthorizationService
import com.singularity.todo.feature.calendarSync.auth.GoogleAuthorizer
import com.singularity.todo.feature.calendarSync.auth.GoogleClientIdResolver
import com.singularity.todo.feature.calendarSync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendarSync.auth.GoogleTokenGateway
import com.singularity.todo.feature.calendarSync.auth.SecureStorageGoogleCredentialStore
import com.singularity.todo.feature.calendarSync.auth.SupabaseGoogleTokenGateway
import com.singularity.todo.feature.calendarSync.data.GoogleCalendarSettingsRepositoryImpl
import com.singularity.todo.feature.calendarSync.data.google.GoogleCalendarEventSource
import com.singularity.todo.feature.calendarSync.domain.model.ImportWindow
import com.singularity.todo.feature.calendarSync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendarSync.domain.port.GoogleCalendarSettingsRepository
import com.singularity.todo.feature.calendarSync.presentation.CalendarSyncViewModel
import com.singularity.todo.feature.calendarSync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendarSync.sync.DirtyHashProvider
import com.singularity.todo.feature.calendarSync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendarSync.sync.GoogleSyncEngine
import com.singularity.todo.feature.calendarSync.sync.GoogleTaskApplier
import com.singularity.todo.feature.calendarSync.work.GoogleSyncPeriodicTrigger
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Calendar sync feature DI module.
 *
 * Registers [CalendarSyncViewModel], [CalendarSyncOrchestrator], and [DirtyHashProvider].
 *
 * The [CalendarSyncOrchestrator] is a singleton that must be started once
 * at app startup via [CalendarSyncOrchestrator.start]. On Android, call:
 *
 * ```
 * val orchestrator: CalendarSyncOrchestrator by koinLazy()
 * orchestrator.start()
 * ```
 *
 * in [com.singularity.todo.SingularityApp.onCreate] after `startKoin`.
 *
 * The [CalendarSyncRepository] binding is registered by the platform module BEFORE this
 * module is loaded (platform modules are registered before feature modules in Modules.kt):
 * - Android: [CalendarSyncSettingsRepositoryImpl][com.singularity.todo.feature.calendarSync.data.CalendarSyncSettingsRepositoryImpl]
 * - JVM: [NoopCalendarSyncRepositoryImpl][com.singularity.todo.feature.calendarSync.data.NoopCalendarSyncRepositoryImpl]
 *
 * [CalendarAppQueries] is also registered by the platform module:
 * - Android: [AndroidCalendarAppQueries][com.singularity.todo.feature.calendarSync.data.AndroidCalendarAppQueries]
 * - JVM: [JvmCalendarAppQueries][com.singularity.todo.feature.calendarSync.data.JvmCalendarAppQueries]
 *
 * The Google sync pass has no orchestrator here: [GoogleSyncCoordinator] is the entry point,
 * and *when* it runs is the per-platform [GoogleSyncPeriodicTrigger] binding, declared in each
 * platform module for the same reason [com.singularity.todo.core.sync.SyncPeriodicTrigger]
 * is — "which platform am I on" must not be answered by a type test on an injected dependency.
 */
// A DI module is a list of bindings, and the reason each binding is shaped the way
// it is — singleton or factory, this dependency or that one — is worth more here
// than the line budget. Splitting the module would either move the rationale away
// from the binding it explains, or hide `single { get() }` calls from the Koin
// compiler plugin that checks the graph at compile time.
@Suppress("LongMethod")
fun calendarSyncModule(): Module = module {
    // ─── Google Calendar ───────────────────────────────────────────────
    //
    // The credential store is a singleton: it is the only thing that knows where a refresh
    // token lives, and two instances would mean two opinions about whether the user is
    // connected.
    single<GoogleCredentialStore> { SecureStorageGoogleCredentialStore(get()) }

    // The user's Google *choices* are not a secret, so they live in the ordinary preference
    // store rather than the keystore — and they deliberately share the `calendar_sync`
    // DataStore with the system-calendar settings under a `google_cal_` key prefix.
    single<GoogleCalendarSettingsRepository> {
        GoogleCalendarSettingsRepositoryImpl(get(qualifier = named("calendar_sync")))
    }

    // The one place the import window is chosen.
    //
    // It was previously a default argument on both GoogleCalendarEventSource and
    // GoogleSyncEngine, plus a direct `ImportWindow.DEFAULT` read in the settings screen —
    // three sites that could disagree with no compiler error, and a screen that would
    // describe a window the pass does not use. Bound once here and injected into all
    // three.
    //
    // A `single`, not a factory: `ImportWindow` is immutable and holds no profile, so
    // every consumer wants the same instance and `CalendarSyncUiState` copies it by value.
    single { ImportWindow.DEFAULT }

    // The client id is a public identifier, so a build-time value is legitimate; see
    // GoogleClientIdConfig for why a user's pasted value still outranks it.
    single { GoogleClientIdResolver() }

    // ─── The Google sign-in ──────────────────────────────────────────────
    //
    // The consent itself is not here: it is a platform activity, and the screen
    // owns the launcher that runs it. What is here is everything around it — the
    // client id, the redemption, the store.
    //
    // The gateway to the project's own server is common: the function is deployed
    // once and every platform talks to it the same way. Whether the *redemption*
    // happens on the device or on the server is the platform's business, and the
    // redeemer binding says which — see the platform modules.
    single<GoogleTokenGateway> { SupabaseGoogleTokenGateway(get()) }

    // The sign-in itself: prepare the request, redeem the grant, write the credential.
    //
    // The redeemer is built per platform rather than here, because the two platforms
    // redeem in different places: the desktop can do it on the device, and Android
    // cannot — its code can only be redeemed by whoever holds the client secret.
    single<GoogleAuthorizer> {
        GoogleAuthorizationService(
            clientIdResolver = get(),
            secureStorage = get<SecureStorage>(),
            redeemer = get(),
            revoker = get(),
            credentialStore = get(),
        )
    }

    // The event source is a factory, not a singleton. It is scoped to one profile — it holds
    // a `userId` and reads that profile's credential — and a calendar sync is per-user, so a
    // single shared instance would read whichever profile happened to be asked about last.
    //
    // The id is taken from `CurrentUser.current` rather than a `UserId` binding, because
    // there is no `UserId` in the graph: the id is a property of the session, and
    // `CurrentUser` is the one place that already knows how to derive it.
    factory<CalendarEventSource> {
        GoogleCalendarEventSource(
            accessTokens = get(),
            userId = get<CurrentUser>().current.value,
            clock = get(),
            importWindow = get(),
        )
    }

    // DirtyHashProvider — depends only on TaskRepository + CalendarSyncRepository
    single { DirtyHashProvider(get(), get()) }

    // ─── The Google sync pass ───────────────────────────────────────────
    //
    // Registered as a factory, not a singleton, for the same reason the event source is:
    // both are bound to one profile. A singleton engine would keep the first profile's
    // `userId` for the life of the process and sync the second profile's calendar into the
    // first profile's tasks — a cross-account write, which is the worst bug this feature
    // could have.
    factory {
        GoogleTaskApplier(
            taskRepository = get(),
            reminderRepository = get(),
            // Named rather than positional: the argument list grew when the alarm re-arm was
            // added, and a positional call would have silently bound the scheduler to a
            // repository instead of failing to compile.
            reminderScheduler = get(),
            shadowDao = get(),
            importDao = get(),
            userId = get<CurrentUser>().current,
            clock = get(),
            idGenerator = get(),
        )
    }

    // Registered as its own factory so a caller that legitimately wants a long-lived engine
    // for one profile can resolve one. The coordinator does not: it builds per pass, because
    // the engine captures the active user by value.
    factory {
        GoogleSyncEngine(
            eventSource = get(),
            shadowDao = get(),
            stateDao = get(),
            importDao = get(),
            userId = get<CurrentUser>().current.value,
            clock = get(),
            importForeignEvents = { get<GoogleCalendarSettingsRepository>().observeImportForeignEvents().first() },
            importWindow = get(),
            applier = get(),
        )
    }

    // The coordinator — a factory, not a singleton, and the reason is the constructor: it
    // takes the active profile as a `UserId` *value*, so an instance is a snapshot of whoever
    // was signed in when it was built. Resolved at app startup — which is when the trigger asks
    // — a `single` would capture `UserId.anonymous` from a cold process and decline every pass
    // forever, or capture one real profile and sync the next one's calendar into it.
    factory {
        GoogleSyncCoordinator(
            // Resolves the factory above rather than rebuilding the engine here. The two
            // spellings of that construction were duplicated for one commit, which is exactly
            // how a dependency list drifts: one copy gets a new argument and the other does not.
            // The coordinator declares `() -> GoogleSyncPass`, and the engine implements it, so
            // returning a fresh instance per call keeps the per-profile snapshot this needs.
            engineProvider = { get<GoogleSyncEngine>() },
            googleSettings = get(),
            credentialStore = get(),
            currentUser = get<CurrentUser>().current,
            clock = get(),
        )
    }

    // CalendarSyncOrchestrator — singleton. Call .start() once at app startup.
    single {
        CalendarSyncOrchestrator(
            get(),
            createBackgroundScope(crashReportingFailureHandler(get())),
            get(),
            get(),
            get(),
        )
    }

    // ViewModel bound to navigation lifecycle — cancelled when the screen leaves the back stack.
    // viewModel (not factory): the scope must be closed when the VM is cleared — a factory
    // registration would leak it.
    //
    // `eventSource` is passed as a lambda rather than an instance. The source is a Koin
    // factory bound to one profile, so resolving it once here would pin the screen to
    // whichever profile was active when the ViewModel was created, and a profile switch
    // would then read the previous account's calendars. Resolving per call keeps the
    // binding per-call and the ViewModel profile-agnostic.
    viewModel<CalendarSyncViewModel> {
        CalendarSyncViewModel(
            syncRepo = get(),
            calendarProvider = get(),
            scheduler = get(),
            appQueries = get(),
            orchestrator = get(),
            googleSettings = get(),
            credentialStore = get(),
            currentUser = get(),
            eventSource = { get<CalendarEventSource>() },
            // The Google half's "Sync Now". Resolved per call because the coordinator is a
            // factory holding a snapshot of the signed-in profile — see its own registration.
            googleSync = get(),
            importWindow = get(),
            googleAuthorization = get(),
            crashReporter = get(),
        )
    }
}
