package com.singularity.todo.feature.backup

import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.ui.MviIntent

/**
 * User actions dispatched into [BackupViewModel].
 *
 * Replaces the seven public methods the ViewModel used to expose, so every entry
 * point goes through the same `onIntent` seam and `IntentMethodName` can police it.
 */
sealed interface BackupIntent : MviIntent {
    /** Export a backup to [destPath]. */
    data class Export(val destPath: String) : BackupIntent

    /** Export to a timestamped default path under the working directory. */
    data object CreateBackup : BackupIntent

    /** Restore from an existing backup file. */
    data class Import(val sourcePath: String) : BackupIntent

    /** Export current settings as a shareable JSON snapshot. */
    data object ExportSettingsSnapshot : BackupIntent

    /** Import settings from a JSON snapshot string. */
    data class ImportSettingsSnapshot(val json: String) : BackupIntent

    data class Delete(val backupId: BackupId) : BackupIntent

    data class Push(val backupId: BackupId) : BackupIntent
}
