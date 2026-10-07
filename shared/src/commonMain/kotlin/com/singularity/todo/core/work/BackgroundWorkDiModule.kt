package com.singularity.todo.core.work

import com.singularity.todo.core.sync.SyncRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The background job catalogue.
 *
 * One list in common code, and both platforms resolve scheduler calls against it — which is
 * what lets `BackgroundJobWorker` take a job id in its input data instead of needing a
 * per-job worker class, and what makes "this id is not a job" a loud error rather than a
 * silent no-op.
 *
 * Composed into `domainModule()` as a **list element**, not via `includes()`: an `includes()`
 * creates a child scope whose bindings are invisible to sibling modules at parent level
 * (ADR `2026-09-27-di-module-aggregator-narrative`).
 */
fun backgroundWorkModule(): Module = module {
    single { SyncPushJob(get<SyncRepository>()) }
    single { PruneLlmUsageJob(get()) }

    // `RoomUsageRecorder` rather than `LlmUsageDao`: the recorder owns the clock and the
    // day arithmetic, and re-deriving the cutoff here would put a second copy of the
    // retention rule in the file that decides when to run the job.
    single<BackgroundJobCatalog> {
        ListBackgroundJobCatalog { listOf(get<SyncPushJob>(), get<PruneLlmUsageJob>()) }
    }

    single { BackgroundWorkBootstrapper(get<BackgroundWorkScheduler>()) }
}

/**
 * A catalogue backed by a list.
 *
 * Deliberately not a `Map` built eagerly in the constructor: `get()` inside a Koin `single`
 * resolves lazily, and building the map at construction time would force every job's
 * dependencies to resolve at graph-build time rather than at first use.
 */
class ListBackgroundJobCatalog(private val resolveJobs: () -> List<BackgroundJob>) : BackgroundJobCatalog {

    /**
     * A catalogue over an already-known list. Keeps the direct-construction call sites
     * — tests, and any caller that genuinely has its jobs — reading the way they did.
     */
    constructor(jobs: List<BackgroundJob>) : this({ jobs })

    /**
     * Built on first use, not in the constructor.
     *
     * The comment above this class has always said the map is deliberately not built
     * eagerly, so that a job's dependencies "resolve at first use rather than at
     * graph-build time". The intent was right and the mechanism did not deliver it:
     * the DI definition passed `listOf(get<SyncPushJob>())`, which Koin evaluates while
     * running the `single`, and `associateBy` then ran in the constructor as well. Both
     * were eager.
     *
     * Eager is not a style question here — it is a cycle. `SyncPushJob` needs
     * `SyncRepository`, `SyncRepository` needs `SyncEngine`, and `SyncEngine` needs
     * `SyncWorkScheduler`; on Desktop that scheduler is the `JvmSyncWorkScheduler` which
     * needs `BackgroundWorkScheduler`, which needs this catalogue. Resolving the
     * catalogue therefore recursed until the stack ran out, and
     * `koin-compiler-plugin` did not report it because the binding is a lambda it cannot
     * analyse across the module boundary. See
     * `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`.
     */
    private val jobs: Map<String, BackgroundJob> by lazy { resolveJobs().associateBy { it.id } }

    override fun find(jobId: String): BackgroundJob? = jobs[jobId]

    override fun all(): List<BackgroundJob> = jobs.values.toList()
}
