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
    class UnsupportedArchiveVersion(val version: Int, val readableVersions: Set<Int>) :
        BackupError("The archive's version is $version, and this build reads versions ${readableVersions.sorted().joinToString(" and ")}")

    /**
     * The archive's bytes could not be moved — read from the source, or written to
     * the sink.
     *
     * Not a wrong password: the bytes never reached the cipher, or the plaintext
     * never reached the disk. A file that is not there, a disk that is full, a
     * device that fell over mid-read. The person can act on this one — the archive
     * is damaged or the destination refused it, not their password.
     *
     * The message names **both directions** because one catch covers both: the
     * cipher reads the source and writes the sink in the same call, and
     * `commonMain` cannot tell whose failure it was. Calling the whole class
     * "unreadable" was the lie the earlier version told a person whose disk was
     * full.
     */
    class Unusable(cause: Throwable? = null) : BackupError("The archive could not be read or written", cause)

    /**
     * The chain of deltas does not hold together.
     *
     * A chain is a base and the deltas that follow it, and the archive refuses to
     * pretend it read one: an entry whose index is not where it should be, a chain
     * shorter than its own head says (the tail was dropped), or extra bytes after
     * the last entry. Applying the entries before the break and calling the result
     * restored is the failure this case exists to prevent — a restore that returns
     * yesterday's data and says nothing.
     */
    class BrokenChain(reason: String, cause: Throwable? = null) :
        BackupError("The archive's chain of changes is broken: $reason", cause)

    /**
     * The archive cannot be encrypted without a password.
     *
     * An archive without a password is the archive this feature exists to stop
     * writing, so it is refused rather than quietly sent in the clear.
     */
    class MissingPassword : BackupError("The archive cannot be encrypted without a password")
}
