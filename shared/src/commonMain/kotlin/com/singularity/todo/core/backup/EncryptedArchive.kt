package com.singularity.todo.core.backup

import com.singularity.todo.core.security.ArchiveCrypto

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
        requirePassword(password)
        val salt = crypto.randomBytes(SALT_BYTES)
        val nonce = crypto.randomBytes(NONCE_BYTES)
        val header = header(salt, nonce)
        val key = crypto.deriveKey(password, salt)
        return try {
            header + crypto.seal(key, nonce, plain, header)
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
    @Suppress("TooGenericExceptionCaught")
    fun open(container: ByteArray, password: CharArray): ByteArray {
        requirePassword(password)
        val header = readHeader(container)
        val parts = readHeaderParts(header)
        val key = crypto.deriveKey(password, parts.salt)
        return try {
            crypto.open(key, parts.nonce, container.copyOfRange(HEADER_BYTES, container.size), header)
        } catch (error: BackupError) {
            throw error
        } catch (error: Exception) {
            throw BackupError.WrongPassword(error)
        } finally {
            key.fill(0)
        }
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
        const val SALT_BYTES = 16

        /** The GCM nonce's length: 12 bytes, as GCM expects. */
        const val NONCE_BYTES = 12

        /** The length of the header: magic, version, salt and nonce. */
        const val HEADER_BYTES = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES
    }
}
