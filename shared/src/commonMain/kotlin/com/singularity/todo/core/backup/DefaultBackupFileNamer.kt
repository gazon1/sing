package com.singularity.todo.core.backup

/**
 * Port for generating deterministic backup file names from a timestamp.
 * Allows tests to use a fixed name instead of one derived from wall-clock time.
 */
interface BackupFileNamer {
    /** Returns a relative backup file name (no path separator prefix). */
    fun nextBackupName(timestampMs: Long): String
}

/** Production default — uses a timestamp in the filename. */
object DefaultBackupFileNamer : BackupFileNamer {
    override fun nextBackupName(timestampMs: Long): String = "singularity_backup_$timestampMs.zip"
}
