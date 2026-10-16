package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.platform.monoClockMillis
import com.singularity.todo.core.sync.DataStoreSyncPrefs
import com.singularity.todo.core.sync.DeviceIdProvider
import com.singularity.todo.core.sync.HlcFactory
import com.singularity.todo.core.sync.OutboxService
import com.singularity.todo.core.sync.PatchRetryPolicy
import com.singularity.todo.core.sync.PostgrestSyncRpc
import com.singularity.todo.core.sync.PullEventHandler
import com.singularity.todo.core.sync.PullPhase
import com.singularity.todo.core.sync.PushPhase
import com.singularity.todo.core.sync.RemoteConfigRepository
import com.singularity.todo.core.sync.RemoteConfigRepositoryImpl
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.sync.RoomSyncStateRepository
import com.singularity.todo.core.sync.SeedPlanner
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncAuthBridge
import com.singularity.todo.core.sync.SyncBootstrapper
import com.singularity.todo.core.sync.SyncCoordinator
import com.singularity.todo.core.sync.SyncDocumentWriter
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SyncEngineState
import com.singularity.todo.core.sync.SyncPatchBuilder
import com.singularity.todo.core.sync.SyncPrefs
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.SyncRepositoryImpl
import com.singularity.todo.core.sync.SyncRpc
import com.singularity.todo.core.sync.SyncRunner
import com.singularity.todo.core.sync.SyncStateRepository
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import com.singularity.todo.feature.sync.presentation.SyncViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Sync-layer dependency graph.
 *
 * Extracted from [coreModule] so the sync write path (OutboxService, PushPhase,
 * PullPhase, SyncEngine) is independently verifiable and the DI graph stays
 * within Koin's compile-time-verifiable scope.
 *
 * ## Graph order
 *
 * `SyncAuthBridge` is registered before `SyncEngine` so the engine's `syncAuthBridge`
 * constructor parameter resolves immediately when the engine is first accessed.
 * All other bindings are lazy singletons — they are instantiated on first `get()`.
 * Because `OutboxService`, `PushPhase`, and `PullPhase` are also singletons, the
 * second and subsequent `get<SyncEngine>()` calls return the cached instance without
 * re-executing the binding block.
 *
 * ## SeedPlanner circular reference note
 *
 * `SeedPlanner` holds `{ get<SyncEngine>().enqueue(entity) }`.  The lambda is NOT
 * invoked during DI construction — it is called at bootstrap time by
 * `SyncBootstrapper` after `SyncEngine` has already been instantiated.  The
 * reference therefore does not cause a cycle at resolution time.
 */
fun syncModule(): org.koin.core.module.Module = module {
    // ─── Transport / RPC ───────────────────────────────────────────────────

    // HlcFactory is internal; SyncEngine depends on it.
    single {
        HlcFactory(
            deviceIdProvider = get(),
            clock = get(),
            scope = get(),
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

    // ─── SyncEngine bootstrap ──────────────────────────────────────────────

    // Bridges auth session changes to the sync scheduler.
    // Extracted from SyncEngine.init {} so the session-collecting logic owns its
    // own scope and can be tested independently.
    single {
        SyncAuthBridge(
            authRepository = get(),
            scheduler = get(),
            scope = get(),
        )
    }

    // SyncEngine is internal — feature modules must use SyncRepository.
    // Takes the per-scope state repository (for LSN tracking), the scope provider
    // (whose (owner, profile) the cycle applies to), the shadow store (the base a
    // diff is taken against) and SyncWorkScheduler (for auth-session init).
    //
    // PushPhase and PullPhase are constructed here so they receive the same
    // SyncEngineState holder that the engine exposes. All three share the same
    // MutableStateFlows and the same SyncPhaseReporter, keeping phase-ending
    // rules in one place.
    //
    // #177 fix: SyncBootstrapper is created AFTER the engine, and is given the engine
    // as a constructor parameter. Its init {} runs during this call and registers all
    // pull handlers. Previously it was a standalone single {} that nobody retrieved, so
    // Koin never instantiated it and zero handlers were registered in production.
    single {
        val state =
            SyncEngineState(
                log = Logger.withTag("SyncEngine"),
                crashReporter = get(),
            )
        // Outbox write path: built after state so it can access state.phases.
        val outboxService =
            OutboxService(
                outboxDao = get(),
                patchBuilder = get(),
                scopeProvider = get(),
                engineState = state,
                monoNow = { monoClockMillis },
            )
        val pushPhase =
            PushPhase(
                api = get(),
                authRepository = get(),
                outboxDao = get(),
                deadLetterDao = get(),
                shadowDao = get(),
                idGenerator = get(),
                scopeProvider = get(),
                patchBuilder = get(),
                monoNow = { monoClockMillis },
                phases = state.phases,
                writerProvider = { get<SyncDocumentWriter>() },
                retryPolicy = get(),
                scope = get(),
            )
        // getHandlers lambda uses get<SyncEngine>() — Koin resolves engine as a singleton,
        // so this returns the same instance. The lambda is not invoked during construction.
        val pullPhase =
            PullPhase(
                api = get(),
                authRepository = get(),
                stateRepository = get(),
                clock = get(),
                scopeProvider = get(),
                phases = state.phases,
                getHandlers = { get<SyncEngine>().handlers },
                scope = get(),
                hlcFactory = get(),
            )
        SyncEngine(
            log = Logger.withTag("SyncEngine"),
            api = get(),
            authRepository = get(),
            idGenerator = get(),
            stateRepository = get(),
            scopeProvider = get(),
            shadowDao = get(),
            patchBuilder = get(),
            outboxService = outboxService,
            _outboxDao = get(),
            _deadLetterDao = get(),
            writerProvider = { get<SyncDocumentWriter>() },
            scheduler = get(),
            retryPolicy = get(),
            clock = get(),
            scope = get(),
            crashReporter = get(),
            hlcFactory = get(),
            state = state,
            pushPhase = pushPhase,
            pullPhase = pullPhase,
            bootstrapper = null,
            syncAuthBridge = get(),
        )
    }

    // Force bootstrapper instantiation so its init {} runs and registers handlers.
    //
    // There is deliberately **no** `single { get<SyncEngine>() }` here. There used to
    // be, and it was a second definition of the same type: in Koin the last
    // definition wins, so this one replaced the real engine binding — and then the
    // bootstrapper's `get<SyncEngine>()` resolved *this* binding, which resolves
    // itself. The result was a `StackOverflowError` on the first MCP tool call,
    // after `singularity-todo MCP server started` had already been printed (#512).
    //
    // The comment that used to sit here claimed "the engine single {} has executed
    // above, so get<SyncEngine>() returns the cached engine". That was true only if
    // something had already forced the engine; nothing had, because every binding is
    // lazy until something asks for it.
    // PullEventHandler: extracted event-handling logic (protocol version guard,
    // JsonObject validation, CREATED/UPDATED/RESTORED/DELETED dispatch) so it lives
    // in one place and the Konsist rule "SyncBootstrapper must not own event-handling
    // logic" stays green. Created before SyncBootstrapper so it can be passed in.
    single {
        PullEventHandler(writer = get())
    }

    single {
        SyncBootstrapper(engine = get<SyncEngine>(), writer = get(), pullEventHandler = get())
    }

    // Backoff policy for rejected patches. One instance so the outbox, the push path
    // and the settings screen all agree on what "too many attempts" means.
    //
    // Explicit lambda, not `singleOf(::PatchRetryPolicy)`: Koin resolves the constructor
    // reflectively and does NOT apply Kotlin default arguments, so `singleOf` demands a
    // `Long` in the graph for `baseDelayMs` and `maxDelayMs` and fails at resolution time
    // with NoDefinitionFoundException. Constructing it here applies those defaults.
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
    single<RemoteConfigPort> {
        com.singularity.todo.core.config.RemoteConfigCacheRepositoryImpl(
            cacheDao = get(),
            syncApi = get(),
            log = get(),
            clock = get(),
        )
    }

    // SyncDocumentWriter: the one statement of "which repository stores which DocType".
    // Registered before the engine and the bootstrapper so both get the same table —
    // two lists would be two answers to that question. See #203.
    single {
        SyncDocumentWriter(
            taskRepo = get<TaskRepository>(),
            noteRepo = get<NotesRepository>(),
            projectRepo = get<ProjectsRepository>(),
            tagRepo = get<TagsRepository>(),
            tagGroupRepo = get<TagGroupRepository>(),
            timeTrackingRepo = get<TimeTrackingRepository>(),
        )
    }

    // SeedPlanner: the one-time upload of data that existed before sign-in. It
    // shares the bootstrapper's reach into the feature repositories for the same
    // reason — a bootstrapper is the one place that knows every entity type.
    single {
        SeedPlanner(
            stateRepository = get(),
            enqueue = { entity -> get<SyncEngine>().enqueue(entity) },
            taskRepo = get<TaskRepository>(),
            noteRepo = get<NotesRepository>(),
            projectRepo = get<ProjectsRepository>(),
            tagRepo = get<TagsRepository>(),
            tagGroupRepo = get<TagGroupRepository>(),
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
}
