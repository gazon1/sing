package com.singularity.todo.core.work

import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Background job that creates a local backup when auto-backup is enabled.
 * Runs once per day. Idempotent — a failed run is retried on next schedule.
 */
class BackupJob(
    private val backupRepository: BackupRepository,
    private val settings: SettingsRepository,
) : BackgroundJob {
    override val id: String = ID

    override suspend fun run(): JobOutcome = runCatching {
        val enabled = settings.autoBackupEnabled.first()
        if (!enabled) return@runCatching
        backupRepository.createBackup()
    }.fold(
        onSuccess = { JobOutcome.Success },
        onFailure = { JobOutcome.Failed(it) },
    )

    companion object {
        const val ID = "backup"
    }
}
