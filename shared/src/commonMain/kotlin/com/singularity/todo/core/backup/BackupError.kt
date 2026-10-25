package com.singularity.todo.core.backup

/**
 * The failures the archive layer reports.
 *
 * They are the domain's own vocabulary: a caller shows the message and decides
 * what to do, and every case here is a thing a person can act on.
 */
sealed class BackupError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedFormatVersion(val version: Int) : BackupError("formatVersion $version > current")
    class UnsupportedSchemaVersion(val version: Int) : BackupError("schemaVersion $version > current")
    class SchemaTooOld(val version: Int) :
        BackupError(
            "schemaVersion $version < minimum supported ${BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION}",
        )
    class ChecksumMismatch(val expected: String, val actual: String) :
        BackupError(
            "checksum mismatch: expected $expected, got $actual",
        )
    class MalformedManifest(reason: String) : BackupError("malformed manifest: $reason")
    class FileNotFound(val path: String) : BackupError("file not found: $path")

    /**
     * The bytes are not an encrypted archive.
     *
     * Either they are an archive from before the encryption, or they are not an
     * archive at all. Either way the bucket holds something this build cannot open,
     * and saying "not encrypted" is more useful than a checksum mismatch.
     */
    class NotEncrypted : BackupError("The archive in the cloud is not encrypted")

    /**
     * The password does not open the archive.
     *
     * The tag of the encrypted archive does not match, which is what a wrong
     * password looks like from the outside — and the same thing a tampered archive
     * looks like. The message names the action the person can take.
     */
    class WrongPassword(cause: Throwable? = null) : BackupError("The password does not open the archive", cause)

    /** The container's version is newer than this build understands. */
    class UnsupportedArchiveVersion(val version: Int) : BackupError("archive version $version > current")

    /**
     * The archive cannot be encrypted without a password.
     *
     * An archive without a password is the archive this feature exists to stop
     * writing, so it is refused rather than quietly sent in the clear.
     */
    class MissingPassword : BackupError("The archive cannot be encrypted without a password")
}
