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

    /**
     * Reads the archives that live in the cloud.
     *
     * Push without a way back is half a feature: the person who uploaded an archive
     * has no way to fetch it again, and the app never says why.
     */
    data object LoadRemoteBackups : BackupIntent

    /**
     * Restores an archive that lives in the cloud.
     *
     * The archive is downloaded and imported in one step — the download lands in the
     * app's own backup directory, which the repository owns.
     */
    data class RestoreFromCloud(val remoteRef: String) : BackupIntent

    /**
     * Hand a stored backup to the platform, so the user can keep it outside the app.
     *
     * A backup in the app's own storage is a backup the user cannot get at: it dies
     * with the app, and there is no way to copy it to a laptop or to cloud storage.
     * The system share sheet is the one mechanism every platform already has for
     * "give this file to the user" — on Android it is also where "Save to Files"
     * lives.
     */
    data class SaveToFile(val id: BackupId) : BackupIntent

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
