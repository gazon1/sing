package com.singularity.todo.feature.calendar_sync.di

import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.feature.calendar_sync.auth.GoogleClientIdResolver
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.auth.SecureStorageGoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.data.GoogleCalendarSettingsRepositoryImpl
import com.singularity.todo.feature.calendar_sync.data.google.GoogleCalendarEventSource
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncViewModel
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.DirtyHashProvider
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncEngine
import com.singularity.todo.feature.calendar_sync.sync.GoogleTaskApplier
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
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
 * - Android: [CalendarSyncSettingsRepositoryImpl][com.singularity.todo.feature.calendar_sync.data.CalendarSyncSettingsRepositoryImpl]
 * - JVM: [NoopCalendarSyncRepositoryImpl][com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepositoryImpl]
 *
 * [CalendarAppQueries] is also registered by the platform module:
 * - Android: [AndroidCalendarAppQueries][com.singularity.todo.feature.calendar_sync.data.AndroidCalendarAppQueries]
 * - JVM: [JvmCalendarAppQueries][com.singularity.todo.feature.calendar_sync.data.JvmCalendarAppQueries]
 *
 * The Google sync pass has no orchestrator here: [GoogleSyncCoordinator] is the entry point,
 * and *when* it runs is the per-platform [GoogleSyncPeriodicTrigger] binding, declared in each
 * platform module for the same reason [com.singularity.todo.core.sync.SyncPeriodicTrigger]
 * is — "which platform am I on" must not be answered by a type test on an injected dependency.
 */
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

    // Not registered here: the token client needs a client id, and the sign-in screen
    // constructs it from whatever GoogleClientIdResolver returns — the user's paste if
    // there is one, the build-time value otherwise. Binding it with a placeholder id would
    // turn a missing configuration into an OAuth error from Google.

    // The event source is a factory, not a singleton. It is scoped to one profile — it holds
    // a `userId` and reads that profile's credential — and a calendar sync is per-user, so a
    // single shared instance would read whichever profile happened to be asked about last.
    //
    // The id is taken from `CurrentUser.current` rather than a `UserId` binding, because
    // there is no `UserId` in the graph: the id is a property of the session, and
    // `CurrentUser` is the one place that already knows how to derive it.
    factory<CalendarEventSource> {
        GoogleCalendarEventSource(
            credentials = get(),
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
            crashReporter = get(),
        )
    }
}
