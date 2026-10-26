package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.readExactly
import com.singularity.todo.core.security.ArchiveCrypto
import com.singularity.todo.core.security.ArchiveCryptoParams
import com.singularity.todo.core.security.ArchiveTagMismatchException

/**
 * The encrypted archive: what sits in the bucket, and what comes back out of it.
 *
 * ## What it protects
 *
 * The bucket's policies keep one account's archives out of another account's reach,
 * but a service-role key reads the whole bucket, and a zip read that way is a
 * database dump in plain text. The RLS of the tables does not apply to the object
 * store, so the archive has to be opaque to anyone who holds the bucket's keys —
 * including the operator of the project.
 *
 * So the archive is encrypted **on the device**, before it leaves: the bucket holds
 * ciphertext, and the key is the person's password. The server never sees it.
 *
 * ## The container
 *
 * ```
 * magic (4) | version (1) | salt (16) | nonce (12) | ciphertext + GCM tag
 * ```
 *
 * - **magic** says "this is an encrypted archive" — so an archive from before the
 *   encryption is refused with a sentence that names the problem, instead of
 *   failing as a checksum mismatch;
 * - **version** says how to read the rest;
 * - **salt** feeds the key derivation, so two archives under one password do not
 *   share a key;
 * - **nonce** is the GCM nonce, and it is fresh for every archive.
 *
 * The header is the authenticated data: it is covered by the tag, so neither the
 * salt nor the nonce nor the version can be changed without the archive ceasing to
 * open. That is why the header is passed to the cipher as AAD rather than being
 * trusted.
 *
 * ## What it does not protect
 *
 * The **name** of the object is not encrypted. A bucket listing shows the file name
 * and its timestamp, and it always will — the name is what the list is sorted by
 * and what the download addresses. What is protected is the content: the archive's
 * rows, notes and attachments.
 *
 * The password is not stored anywhere. Not in the settings, not in the keychain,
 * not in the archive. The person who pushed it is the one who can open it, and a
 * forgotten password means a lost archive — the honest price of a key that the
 * server never has.
 */
class EncryptedArchive(private val crypto: ArchiveCrypto) {

    /**
     * The encrypted form of [plain].
     *
     * @throws BackupError.MissingPassword when the password is empty.
     */
    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val sink = ByteArraySink()
        seal(ByteArraySource(plain), sink, password)
        return sink.bytes()
    }

    /**
     * The encrypted form of what [source] yields, written to [sink].
     *
     * The archive is never held in memory whole: it is read in pieces and
     * encrypted in pieces, so the app's memory carries a chunk of the account
     * rather than all of it.
     *
     * @throws BackupError.MissingPassword when the password is empty.
     */
    fun seal(source: ByteSource, sink: ByteSink, password: CharArray) {
        requirePassword(password)
        val salt = crypto.randomBytes(SALT_BYTES)
        val nonce = crypto.randomBytes(NONCE_BYTES)
        val header = header(salt, nonce)
        val key = crypto.deriveKey(password, salt)
        try {
            // The header goes out first, and it is the authenticated data: it has
            // to be known before the first byte of the archive is encrypted.
            sink.write(header, 0, header.size)
            crypto.seal(source, sink, key, nonce, header)
        } finally {
            // The derived key is the archive's key in the only form it ever takes.
            // It is not the password — the password is wiped by whoever owns it —
            // but this one is ours, and ours is what we clean up after ourselves.
            key.fill(0)
        }
    }

    /**
     * The archive inside [container].
     *
     * @throws BackupError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws BackupError.UnsupportedArchiveVersion when the container's version is
     *   not the one this build reads.
     * @throws BackupError.WrongPassword when the password does not open it.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun open(container: ByteArray, password: CharArray): ByteArray {
        requirePassword(password)
        val source = ByteArraySource(container)
        val header = readHeader(source)
        val parts = readHeaderParts(header)
        val key = crypto.deriveKey(password, parts.salt)
        val sink = ByteArraySink()
        return try {
            crypto.open(source, sink, key, parts.nonce, header)
            sink.bytes()
        } catch (error: BackupError) {
            throw error
        } catch (error: ArchiveTagMismatchException) {
            // The cipher says the tag does not match: the password is wrong.
            throw BackupError.WrongPassword(error)
        } catch (error: Exception) {
            // Anything else is not about the password: the bytes could not be read
            // at all. Saying "wrong password" here sends the person to retype a
            // password that was never the problem.
            throw BackupError.Unreadable(error)
        } finally {
            key.fill(0)
        }
    }

    /**
     * The archive inside what [source] yields, written to [sink].
     *
     * **The sink is written before the password is proven.** The cipher can only
     * check the tag when it has read everything, so what arrives in the sink may
     * be garbage when the password was wrong. A caller that cares writes into a
     * temporary file and moves it into place only after this call returned
     * without throwing.
     *
     * @throws BackupError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws BackupError.UnsupportedArchiveVersion when the container's version is
     *   not the one this build reads.
     * @throws BackupError.WrongPassword when the password does not open it.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun open(source: ByteSource, sink: ByteSink, password: CharArray) {
        requirePassword(password)
        val header = readHeader(source)
        val parts = readHeaderParts(header)
        val key = crypto.deriveKey(password, parts.salt)
        try {
            crypto.open(source, sink, key, parts.nonce, header)
        } catch (error: BackupError) {
            throw error
        } catch (error: ArchiveTagMismatchException) {
            // The cipher says the tag does not match: the password is wrong.
            throw BackupError.WrongPassword(error)
        } catch (error: Exception) {
            // Anything else is not about the password: the bytes could not be read
            // at all. Saying "wrong password" here sends the person to retype a
            // password that was never the problem.
            throw BackupError.Unreadable(error)
        } finally {
            key.fill(0)
        }
    }

    /**
     * The header of what the source yields.
     *
     * @throws BackupError.NotEncrypted when the bytes are too short to be one.
     * @throws BackupError.Unreadable when the bytes could not be read at all — the
     *   device fell over mid-read. The header is read before the cipher is asked for
     *   anything, so a failure here is not a wrong password either, and it must not
     *   escape as a raw I/O error the layer above cannot name.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readHeader(source: ByteSource): ByteArray =
        try {
            source.readExactly(HEADER_BYTES)
        } catch (error: IllegalStateException) {
            // Too few bytes: the source said what it is, and it is not a container.
            throw BackupError.NotEncrypted(error)
        } catch (error: Exception) {
            // The bytes never arrived: the archive is unreadable, not wrong-password.
            throw BackupError.Unreadable(error)
        }

    /**
     * A password is not optional.
     *
     * An empty password would derive a key from nothing, and an archive sealed
     * with it would be readable by anyone who has the bucket. Both directions
     * refuse it by name, rather than the opening one failing with a wrong
     * password it never had.
     *
     * @throws BackupError.MissingPassword when the password is empty.
     */
    private fun requirePassword(password: CharArray) {
        if (password.isEmpty()) throw BackupError.MissingPassword()
    }

    /**
     * The header of [container].
     *
     * @throws BackupError.NotEncrypted when the bytes are too short to be one.
     */
    private fun readHeader(container: ByteArray): ByteArray =
        container.takeIf { it.size >= HEADER_BYTES }?.copyOfRange(0, HEADER_BYTES)
            ?: throw BackupError.NotEncrypted()

    /**
     * The header's salt, nonce and version.
     *
     * @throws BackupError.NotEncrypted when the bytes do not say what they are.
     * @throws BackupError.UnsupportedArchiveVersion when the container's version is
     *   not the one this build reads.
     */
    private fun readHeaderParts(header: ByteArray): Header {
        if (header.copyOfRange(0, MAGIC.length).decodeToString() != MAGIC) throw BackupError.NotEncrypted()
        val version = header[MAGIC.length].toInt() and 0xFF
        if (version != VERSION) throw BackupError.UnsupportedArchiveVersion(version, VERSION)
        return Header(
            salt = header.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_BYTES),
            nonce = header.copyOfRange(MAGIC.length + 1 + SALT_BYTES, HEADER_BYTES),
        )
    }

    private fun header(salt: ByteArray, nonce: ByteArray): ByteArray =
        MAGIC.encodeToByteArray() +
            byteArrayOf(VERSION.toByte()) +
            salt +
            nonce

    /**
     * The header's parts.
     *
     * The salt feeds the key derivation and the nonce is the cipher's; the version
     * has already been checked by the time these are handed out.
     */
    private class Header(val salt: ByteArray, val nonce: ByteArray)

    companion object {
        /** The bytes that say "an encrypted archive follows". */
        const val MAGIC = "SGBK"

        /** The version of the container this build reads and writes. */
        const val VERSION = 1

        /** The salt's length: 16 bytes, as PBKDF2 expects. */
        const val SALT_BYTES = ArchiveCryptoParams.SALT_BYTES

        /** The GCM nonce's length: 12 bytes, as GCM expects. */
        const val NONCE_BYTES = ArchiveCryptoParams.NONCE_BYTES

        /** The length of the header: magic, version, salt and nonce. */
        const val HEADER_BYTES = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES
    }
}
