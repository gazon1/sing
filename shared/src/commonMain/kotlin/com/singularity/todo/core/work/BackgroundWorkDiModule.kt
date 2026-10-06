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

    single<BackgroundJobCatalog> {
        ListBackgroundJobCatalog(listOf(get<SyncPushJob>()))
    }
}

/**
 * A catalogue backed by a list.
 *
 * Deliberately not a `Map` built eagerly in the constructor: `get()` inside a Koin `single`
 * resolves lazily, and building the map at construction time would force every job's
 * dependencies to resolve at graph-build time rather than at first use.
 */
class ListBackgroundJobCatalog(jobs: List<BackgroundJob>) : BackgroundJobCatalog {

    private val jobs: Map<String, BackgroundJob> = jobs.associateBy { it.id }

    override fun find(jobId: String): BackgroundJob? = jobs[jobId]

    override fun all(): List<BackgroundJob> = jobs.values.toList()
}
