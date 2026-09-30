package com.singularity.todo.feature.backup

import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.ui.MviIntent

/**
 * User actions on the backup screen.
 *
 * Dispatched through [BackupViewModel.onIntent]. The public methods on
 * [BackupViewModel] (`createBackup()`, `delete(id)`, …) are thin forwarders to these,
 * so a screen can keep calling the ergonomic name while the intent hierarchy stays the
 * single description of what the user can do.
 */
sealed interface BackupIntent : MviIntent {

    /** Export to a timestamped default path derived from the clock. */
    data object CreateBackup : BackupIntent

    /** Restore from an archive on disk. */
    data class Restore(val sourcePath: String) : BackupIntent

    /** Delete a stored backup. */
    data class Delete(val id: BackupId) : BackupIntent

    /** Upload a stored backup to the remote endpoint. */
    data class Push(val id: BackupId) : BackupIntent

    /** Export settings as a JSON snapshot for the platform share sheet. */
    data object ExportSettingsSnapshot : BackupIntent

    /** Restore settings from a JSON snapshot previously produced by [ExportSettingsSnapshot]. */
    data class ImportSettingsSnapshot(val json: String) : BackupIntent

    /**
     * Import settings from a file the user picked, identified by path or `content://` URI.
     *
     * The read is the ViewModel's job, not the screen's: on Android the picker hands
     * back a SAF URI that only a `ContentResolver` can open, and a Composable has no
     * business holding one. [BackupViewModel] routes the path through
     * [com.singularity.todo.core.files.FileSourceFactory], which already knows both
     * forms, and emits [ImportSettingsSnapshot] once the bytes are decoded.
     */
    data class ImportSettingsFrom(val sourcePath: String) : BackupIntent
}
