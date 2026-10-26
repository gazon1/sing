package com.singularity.todo.core.backup

/**
 * The device's own files are not where they should be.
 *
 * ## What this family is about
 *
 * Everything else in the backup layer is about the *contents* of an archive. This
 * is about the container the contents live in on the device: a backup that was
 * asked for and is not on the disk, a destination that was written and could not
 * be measured afterwards.
 *
 * It is its own family because it is the only failure here that the person can
 * fix by looking at their own device rather than at their password.
 */
sealed class BackupStorageError(message: String, cause: Throwable? = null) : BackupError(message, cause) {

    /** The file is not on the device. */
    class FileNotFound(val path: String) :
        BackupStorageError("file not found: $path")
}
