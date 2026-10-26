package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteSource

/**
 * The container, as the reader sees it.
 *
 * ## What a version means
 *
 * A version is a **meaning**, not a format revision. The reader therefore has to
 * know the *set* of versions it reads, and route on the version it finds:
 *
 * | Version | The reader does this |
 * |---|---|
 * | 1 | the container is one sealed stream; there is nothing after the header |
 * | 2 | the container is a chain: changes first, the base last |
 * | 3 | a chain whose content is sealed under the device key, which the header carries |
 *
 * The refusal names the version the archive has against the versions this build
 * knows — a version from the past is just as unreadable as one from the future,
 * and "0 > current" is a sentence that is only true half of the time.
 */
internal class ArchiveContainer(
    /** The version the archive carries. */
    val version: Int,
    /** Magic, version, salt and nonce. */
    val prefix: ByteArray,
    /** The salt the key is derived from. */
    val salt: ByteArray,
    /** The GCM nonce of the base. */
    val nonce: ByteArray,
    /** The chain's own count. */
    val head: ChainHead,
    /** The data the base's tag authenticates. */
    val header: ByteArray,
    /** The wrapped device key — version 3 only. */
    val keyBlock: ArchiveKeyBlock? = null,
)

/**
 * The device key, wrapped under the password.
 *
 * ## Why the key travels with the archive
 *
 * A scheduled export has no person, and therefore no password — but the archive
 * still has to be openable by the person who owns the account. So the archive
 * carries the key it was sealed with, in a form only the password can open: a
 * small block, sealed under the key the password derives, holding the device key.
 *
 * The block is sealed under a **salt of its own**, not under the archive's: the
 * block is wrapped once per device, and the archive's salt changes with every
 * archive, so tying the two would mean re-wrapping the key for every export.
 */
class ArchiveKeyBlock(
    /** The salt the wrapping key is derived from. */
    val salt: ByteArray,
    /** The nonce of the wrapping. */
    val nonce: ByteArray,
    /** The wrapped device key, with the GCM tag. */
    val ciphertext: ByteArray,
) {
    /** The block as the bytes that go into the header. */
    fun toBytes(): ByteArray = salt + nonce + ciphertext

    companion object {
        /** The block as it is read out of a container. */
        fun fromBytes(bytes: ByteArray): ArchiveKeyBlock {
            val saltLength = ArchiveFormat.SALT_BYTES
            val nonceLength = ArchiveFormat.NONCE_BYTES
            return ArchiveKeyBlock(
                salt = bytes.copyOfRange(0, saltLength),
                nonce = bytes.copyOfRange(saltLength, saltLength + nonceLength),
                ciphertext = bytes.copyOfRange(saltLength + nonceLength, bytes.size),
            )
        }
    }
}

/**
 * Reads the container's header, whichever version it is.
 *
 * **The magic and the version are read first, and everything after them depends
 * on the version** — a version-2 header is longer than a version-1 one, and a
 * reader that read a fixed length would cut a v2 header in half and reject the
 * archive it was written by.
 */
internal object ArchiveContainerReader {

    /**
     * The container's header.
     *
     * @throws ArchiveEnvelopeError.NotEncrypted when the bytes do not say what
     *   they are.
     * @throws ArchiveEnvelopeError.UnsupportedArchiveVersion when the version is
     *   not one this build reads.
     */
    fun read(source: ByteSource): ArchiveContainer {
        val prefix = readPrefix(source)
        val version = prefix[ArchiveFormat.MAGIC.length].toInt() and 0xFF
        val salt = prefix.copyOfRange(
            ArchiveFormat.MAGIC.length + 1,
            ArchiveFormat.MAGIC.length + 1 + ArchiveFormat.SALT_BYTES,
        )
        val nonce = prefix.copyOfRange(ArchiveFormat.MAGIC.length + 1 + ArchiveFormat.SALT_BYTES, prefix.size)

        if (version == 1) {
            return ArchiveContainer(
                version = version,
                prefix = prefix,
                salt = salt,
                nonce = nonce,
                head = ChainHead(count = 0, entriesLength = 0),
                header = prefix,
            )
        }

        val headBytes = source.readOrNull(ArchiveFormat.HEAD_BYTES)
            ?: throw ArchiveEnvelopeError.NotEncrypted(IllegalStateException("a version-$version header ends early"))
        val head = ChainHead.fromBytes(headBytes)

        if (version == 2) {
            return ArchiveContainer(
                version = version,
                prefix = prefix,
                salt = salt,
                nonce = nonce,
                head = head,
                header = prefix + headBytes,
            )
        }

        val keyBytes = source.readOrNull(ArchiveFormat.KEY_BLOCK_BYTES)
            ?: throw ArchiveEnvelopeError.NotEncrypted(IllegalStateException("a version-$version header ends early"))
        return ArchiveContainer(
            version = version,
            prefix = prefix,
            salt = salt,
            nonce = nonce,
            head = head,
            header = prefix + headBytes + keyBytes,
            keyBlock = ArchiveKeyBlock.fromBytes(keyBytes),
        )
    }

    /**
     * The magic, the version, the salt and the nonce.
     *
     * @throws ArchiveEnvelopeError.NotEncrypted when the bytes are too short or
     *   are not an encrypted archive.
     * @throws ArchiveEnvelopeError.UnsupportedArchiveVersion when the version is
     *   not read.
     */
    private fun readPrefix(source: ByteSource): ByteArray {
        val header = source.readOrNull(ArchiveFormat.PREFIX_BYTES)
            ?: throw ArchiveEnvelopeError.NotEncrypted()
        checkMagic(header)
        val version = header[ArchiveFormat.MAGIC.length].toInt() and 0xFF
        if (version !in ArchiveFormat.READABLE_VERSIONS) {
            throw ArchiveEnvelopeError.UnsupportedArchiveVersion(version, ArchiveFormat.READABLE_VERSIONS)
        }
        return header
    }

    /**
     * The bytes say "this is an encrypted archive", or they say nothing of the kind.
     */
    private fun checkMagic(header: ByteArray) {
        if (header.copyOfRange(0, ArchiveFormat.MAGIC.length).decodeToString() != ArchiveFormat.MAGIC) {
            throw ArchiveEnvelopeError.NotEncrypted()
        }
    }
}
