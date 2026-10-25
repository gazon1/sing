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
    class NotEncrypted(cause: Throwable? = null) :
        BackupError("The archive in the cloud is not encrypted", cause)

    /**
     * The password does not open the archive.
     *
     * The tag of the encrypted archive does not match, which is what a wrong
     * password looks like from the outside — and the same thing a tampered archive
     * looks like. The message names the action the person can take.
     */
    class WrongPassword(cause: Throwable? = null) : BackupError("The password does not open the archive", cause)

    /**
     * The container's version is not the one this build reads.
     *
     * The message says what the build reads and what the container carries, and not
     * which of the two is bigger: a version from the past is just as unreadable as
     * one from the future, and "0 > current" is a sentence that is only true half of
     * the time.
     */
    class UnsupportedArchiveVersion(val version: Int, val currentVersion: Int) :
        BackupError("The archive's version is $version, and this build reads $currentVersion")

    /**
     * The archive could not be read at all.
     *
     * Not a wrong password: the bytes never made it to the cipher. A file that is
     * not there, a disk that is full, a device that fell over mid-read. The person
     * can act on this one — the archive is damaged, not their password.
     */
    class Unreadable(cause: Throwable? = null) : BackupError("The archive could not be read", cause)

    /**
     * The archive cannot be encrypted without a password.
     *
     * An archive without a password is the archive this feature exists to stop
     * writing, so it is refused rather than quietly sent in the clear.
     */
    class MissingPassword : BackupError("The archive cannot be encrypted without a password")
}
