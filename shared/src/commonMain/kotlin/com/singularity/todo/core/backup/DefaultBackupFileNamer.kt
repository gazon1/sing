package com.singularity.todo.core.backup

/**
 * Generates deterministic backup file names from a timestamp.
 * Allows tests to use a fixed name instead of one derived from wall-clock time.
 *
 * @param timestampToName transforms a timestamp (epoch ms) into a relative file name.
 *   Default implementation uses `"singularity_backup_$timestamp.zip"`.
 */
class DefaultBackupFileNamer(
    private val timestampToName: (Long) -> String = { ts -> "singularity_backup_$ts.zip" },
) {
    /** Returns a relative backup file name (no path separator prefix). */
    fun nextBackupName(timestampMs: Long): String = timestampToName(timestampMs)
}
