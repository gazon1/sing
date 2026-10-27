package com.singularity.todo.feature.backup

import com.singularity.todo.core.backup.ArchiveEnvelopeError
import com.singularity.todo.core.backup.BackupPayloadError
import com.singularity.todo.core.backup.BackupStorageError
import com.singularity.todo.core.error.AppError

/**
 * A backup failure, said in two ways: a code the crash report groups by, and a
 * sentence a person can act on.
 *
 * ## Why the two are not the same
 *
 * The code says **what kind of thing went wrong**, so that a hundred failures of
 * the same kind are one line in a report. The sentence says **what happened to
 * this person**, so that the screen says "that password does not open the
 * archive" instead of "backup failed".
 *
 * The code is for the machines, the sentence is for the person, and neither is
 * allowed to be the other.
 */
data class BackupFailure(
    /** The code the crash report groups by. */
    val code: String,
    /** The sentence the screen shows. */
    val message: String,
)

/**
 * The failure [error] means, in the project's words.
 *
 * The backup layer reports three families of failure — the container, the payload
 * inside it, and the device's own files — and each case in each family is
 * something a person can act on. The mapping is here, once, so that the screen
 * and the report cannot drift apart: a code that means one thing in the report
 * and another on the screen is worse than no code at all.
 *
 * A failure that is not one of ours keeps the code and the sentence it came with;
 * only the backup's own failures are translated.
 */
fun backupFailure(error: Throwable, fallback: BackupFailure): BackupFailure =
    when (error) {
        is ArchiveEnvelopeError.WrongPassword ->
            BackupFailure(WRONG_PASSWORD, "That password does not open the archive")

        is ArchiveEnvelopeError.NotEncrypted ->
            BackupFailure(NOT_ENCRYPTED, "That file is not an encrypted archive")

        is ArchiveEnvelopeError.MissingPassword ->
            BackupFailure(MISSING_PASSWORD, "A backup needs a password")

        is ArchiveEnvelopeError.UnsupportedArchiveVersion ->
            BackupFailure(UNSUPPORTED_ARCHIVE_VERSION, "That archive is in a format this app does not read")

        is ArchiveEnvelopeError.BrokenChain ->
            BackupFailure(BROKEN_CHAIN, "That archive's chain of changes is broken")

        is ArchiveEnvelopeError.ChainTooLong ->
            BackupFailure(CHAIN_TOO_LONG, "That archive carries more changes than this app reads")

        is BackupPayloadError.ChecksumMismatch ->
            BackupFailure(CHECKSUM_MISMATCH, "That archive's contents do not match its manifest")

        is BackupPayloadError.MalformedManifest ->
            BackupFailure(MALFORMED_MANIFEST, "That archive's manifest cannot be read")

        is BackupPayloadError.UnsupportedFormatVersion ->
            BackupFailure(UNSUPPORTED_FORMAT_VERSION, "That archive's format is newer than this app")

        is BackupPayloadError.UnsupportedSchemaVersion ->
            BackupFailure(UNSUPPORTED_SCHEMA_VERSION, "That archive's data is newer than this app")

        is BackupPayloadError.SchemaTooOld ->
            BackupFailure(SCHEMA_TOO_OLD, "That archive is too old for this app to restore")

        is BackupStorageError.FileNotFound ->
            BackupFailure(FILE_NOT_FOUND, "That backup is not on the device")

        is BackupStorageError.DeviceNotAuthorized ->
            BackupFailure(DEVICE_NOT_AUTHORIZED, "This device is not allowed to back up on its own")

        is AppError -> BackupFailure(error.code, error.message ?: fallback.message)

        // A failure that is not the backup's own keeps its own words: the person
        // is shown what went wrong, and the report is grouped by the fallback.
        else -> BackupFailure(fallback.code, error.message ?: fallback.message)
    }

/** The password does not open the archive. */
const val WRONG_PASSWORD = "backup.wrong_password"

/** The file is not an archive this app can read. */
const val NOT_ENCRYPTED = "backup.not_encrypted"

/** A backup was asked for without a password. */
const val MISSING_PASSWORD = "backup.missing_password"

/** The archive is in a version this app does not read. */
const val UNSUPPORTED_ARCHIVE_VERSION = "backup.unsupported_archive_version"

/** The chain of changes in the archive does not hold together. */
const val BROKEN_CHAIN = "backup.broken_chain"

/** The chain of changes is longer than this app reads. */
const val CHAIN_TOO_LONG = "backup.chain_too_long"

/** The contents do not match the manifest. */
const val CHECKSUM_MISMATCH = "backup.checksum_mismatch"

/** The manifest cannot be read. */
const val MALFORMED_MANIFEST = "backup.malformed_manifest"

/** The archive's format is newer than this app. */
const val UNSUPPORTED_FORMAT_VERSION = "backup.unsupported_format_version"

/** The archive's data is newer than this app. */
const val UNSUPPORTED_SCHEMA_VERSION = "backup.unsupported_schema_version"

/** The archive is too old for this app to restore. */
const val SCHEMA_TOO_OLD = "backup.schema_too_old"

/** The file is not on the device. */
const val FILE_NOT_FOUND = "backup.file_not_found"

/** The device is not allowed to push on its own. */
const val DEVICE_NOT_AUTHORIZED = "backup.device_not_authorized"
