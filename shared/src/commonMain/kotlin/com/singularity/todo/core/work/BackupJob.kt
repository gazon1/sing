package com.singularity.todo.core.work

import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Background job that creates a local backup when auto-backup is enabled.
 * Runs once per day. Idempotent — a failed run is retried on next schedule.
 *
 * ## The cloud half
 *
 * A local backup is a file on this device; it dies with the device. So the run
 * also pushes to the cloud — **when the person has authorized this device to
 * seal archives on its own**.
 *
 * That authorization is what makes the push possible without a person: the
 * device holds a key, wrapped under the person's password, and the archive is
 * sealed under that key. The person who restores it still needs their password
 * to unwrap the key — the bucket never holds anything a stranger can read.
 *
 * A device that was never authorized simply does not push: there is no key to
 * seal with, and an archive nobody can open is not a backup. The check is the
 * repository's, so the job does not know what a key is.
 */
class BackupJob(private val backupRepository: BackupRepository, private val settings: SettingsRepository) :
    BackgroundJob {
    override val id: String = ID

    override suspend fun run(): JobOutcome =
        runCatchingCancellable {
            val enabled = settings.autoBackupEnabled.first()
            if (!enabled) return@runCatchingCancellable
            backupRepository.createBackup().getOrThrow()
            if (backupRepository.isScheduledBackupAuthorized()) {
                backupRepository.pushScheduled().getOrThrow()
            }
        }.fold(
            onSuccess = { JobOutcome.Success },
            onFailure = { JobOutcome.Failed(it) },
        )

    companion object {
        const val ID = "backup"
    }
}
