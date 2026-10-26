package com.singularity.todo.core.backup

/**
 * The container could not be opened.
 *
 * ## What this family is about
 *
 * The envelope is the part of the archive that says "this is an encrypted
 * archive, of this version, under this key". Everything here happened **before**
 * a single row of the person's data was read: the bytes are not an archive, the
 * version is one this build does not read, the password does not open it, or the
 * chain of changes does not hold together.
 *
 * That is worth saying out loud, because the alternative — one flat list of
 * failures — made a caller guess whether a broken archive meant "the password is
 * wrong" or "the checksum does not match". It does not: a checksum lives in the
 * payload, and a payload failure is [BackupPayloadError].
 */
sealed class ArchiveEnvelopeError(message: String, cause: Throwable? = null) : BackupError(message, cause) {

    /**
     * The bytes are not an encrypted archive.
     *
     * Either they are an archive from before the encryption, or they are not an
     * archive at all. Either way the bucket holds something this build cannot
     * open, and saying "not encrypted" is more useful than a checksum mismatch.
     */
    class NotEncrypted(cause: Throwable? = null) :
        ArchiveEnvelopeError("The archive in the cloud is not encrypted", cause)

    /**
     * The password does not open the archive.
     *
     * The tag of the encrypted archive does not match, which is what a wrong
     * password looks like from the outside — and the same thing a tampered
     * archive looks like. The message names the action the person can take.
     */
    class WrongPassword(cause: Throwable? = null) :
        ArchiveEnvelopeError("The password does not open the archive", cause)

    /**
     * The container's version is not the one this build reads.
     *
     * The message says what the build reads and what the container carries, and
     * not which of the two is bigger: a version from the past is just as
     * unreadable as one from the future, and "0 > current" is a sentence that is
     * only true half of the time.
     */
    class UnsupportedArchiveVersion(val version: Int, val readableVersions: Set<Int>) :
        ArchiveEnvelopeError("The archive's version is $version, and this build reads $readableVersions")

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
    class Unusable(cause: Throwable? = null) :
        ArchiveEnvelopeError("The archive could not be read or written", cause)

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
        ArchiveEnvelopeError("The archive's chain of changes is broken: $reason", cause)

    /**
     * The archive cannot be encrypted without a password.
     *
     * An archive without a password is the archive this feature exists to stop
     * writing, so it is refused rather than quietly sent in the clear.
     */
    class MissingPassword :
        ArchiveEnvelopeError("The archive cannot be encrypted without a password")

    /**
     * The chain is longer than this build writes or reads.
     *
     * A chain that only ever grows is a chain that never shrinks, so the export
     * starts a fresh base when the chain passes its bound. Writing a chain past
     * the bound would produce an archive this very build calls broken — a failure
     * at restore time instead of at export time — so the writer refuses it here,
     * by name.
     */
    class ChainTooLong(reason: String) :
        ArchiveEnvelopeError("The archive's chain of changes is too long: $reason")
}
