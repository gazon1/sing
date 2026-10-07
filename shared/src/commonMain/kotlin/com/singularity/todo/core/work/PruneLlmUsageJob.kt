package com.singularity.todo.core.work

import com.singularity.todo.core.observability.RoomUsageRecorder

/**
 * Drops `llm_usage` rows older than the retention window, as a [BackgroundJob].
 *
 * ## Why this job and not auto-backup or archive
 *
 * WS3 asked for three jobs — backup, archive, prune. Only one of them is a real job
 * today, and the other two are traps:
 *
 * - **Auto-backup** has no configuration. There is no `autoBackup` preference, no
 *   interval, no destination policy, and `StubRemoteBackupService` returns the local
 *   path as a pseudo-remote URL and an empty list for remote contents. Writing an
 *   "auto backup" job would mean inventing all of it — and a backup job that silently
 *   does nothing, because the destination is a stub, is exactly the inert-seam defect
 *   this package was built to end. It needs a product decision first.
 * - **Archive/purge of soft-deleted tasks** has no cutoff anywhere in the codebase:
 *   no retention setting, no `TaskDao` method that removes rows, and the archive screen
 *   is the only surface that shows them. Same problem — a job whose policy does not
 *   exist.
 *
 * Prune is different: the arithmetic exists ([RoomUsageRecorder.prune]), the DAO method
 * exists, the table is genuinely unbounded, and **nothing calls it**. Every AI call
 * appends a row and no code path anywhere deletes one, so the table grows for the life
 * of the install. That is a defect with a fix that does not need a product decision.
 *
 * So this ships alone and the other two are recorded rather than faked.
 */
class PruneLlmUsageJob(
    private val usageRecorder: RoomUsageRecorder,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) : BackgroundJob {

    override val id: String = ID

    /**
     * No `try`/`catch` here, and that is deliberate.
     *
     * Both platforms already convert a thrown body into [JobOutcome.Failed]: the JVM
     * executor catches and logs (`JvmBackgroundWorkScheduler.kt:144-151`) and the Android
     * worker turns it into a retry with a capped back-off (`BackgroundJobWorker.kt:41`).
     * Catching here as well would duplicate that, and would need a type to catch —
     * `Throwable` trips detekt's `TooGenericExceptionCaught`, and the concrete alternative
     * is a `java.io` type, which `CommonMainJvmApiTest` correctly rejects because
     * commonMain must compile for Kotlin/Native too.
     *
     * Letting the failure propagate is therefore both the shortest and the only
     * multiplatform-correct spelling. Nothing is deleted on failure, so the table keeps
     * growing rather than losing history, and the next run retries from the same state.
     */
    override suspend fun run(): JobOutcome {
        usageRecorder.prune(retentionDays)
        logger.d { "pruned llm_usage older than $retentionDays day(s)" }
        return JobOutcome.Success
    }

    companion object {
        const val ID = "prune-llm-usage"

        /**
         * 90 days, matching the existing [RoomUsageRecorder.prune] default so the job and
         * a manual call agree.
         *
         * A retention figure is a product decision, so the job does not invent a better
         * one — it uses the number the codebase already had and takes it as a parameter
         * for whoever sets it deliberately.
         */
        const val DEFAULT_RETENTION_DAYS = 90

        private val logger = co.touchlab.kermit.Logger.withTag("PruneLlmUsageJob")
    }
}
