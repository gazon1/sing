package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.security.ArchiveCrypto

/**
 * The archive's key, and the calls that use it.
 *
 * ## Why the key has an owner
 *
 * The derived key is the archive's key in the only form it ever takes. It is not
 * the password — the password is wiped by whoever owns it — but this one is ours,
 * and ours is what we clean up after ourselves: a session that is closed wipes
 * the key it derived, whether it opened the archive or sealed it.
 *
 * ## The two kinds of session
 *
 * - a **password session** derives the key from the password and the archive's
 *   salt. Versions 1 and 2 are entirely made of this;
 * - a **device session** derives the key from the device key and the archive's
 *   salt. Version 3's *content* is sealed this way, so that a scheduled export
 *   can write an archive without a password; the password is still what opens
 *   the key block that carries the device key.
 */
internal class ArchiveCryptoSession(
    private val crypto: ArchiveCrypto,
    /** The key this session holds. */
    private val key: ByteArray,
) {

    /**
     * Seals one change, with its place in the chain as part of what is
     * authenticated.
     *
     * The authenticated data is the header's **prefix** plus the change's
     * **index**: the prefix binds the change to this archive, and the index
     * catches a swap. The head is not in that data, because the head is computed
     * from the changes' own sealed lengths.
     *
     * The nonce is the caller's: every change has one of its own, because a GCM
     * stream cannot be appended to, and it is the only source of randomness the
     * container has.
     */
    fun sealEntry(index: Int, source: ByteSource, sink: ByteSink, prefix: ByteArray, nonce: ByteArray) {
        crypto.seal(source, sink, key, nonce, entryAad(prefix, index))
    }

    /** Opens one change, with the same authenticated data it was sealed with. */
    fun openEntry(index: Int, body: ByteArray, prefix: ByteArray, nonce: ByteArray): ByteArray =
        crypto.open(key, nonce, body, entryAad(prefix, index))

    /** Seals the base, with the whole header as the authenticated data. */
    fun sealBase(source: ByteSource, sink: ByteSink, nonce: ByteArray, header: ByteArray) {
        crypto.seal(source, sink, key, nonce, header)
    }

    /** Opens the base, with the whole header as the authenticated data. */
    fun openBase(source: ByteSource, sink: ByteSink, nonce: ByteArray, header: ByteArray) {
        crypto.open(source, sink, key, nonce, header)
    }

    /** Wipes the key. The session is unusable afterwards. */
    fun close() {
        key.fill(0)
    }

    /**
     * Wraps a device key under this session's key.
     *
     * The block's authenticated data is its **own** marker and salt, never the
     * archive's prefix: the block is wrapped once per device and travels in
     * every archive, so tying it to an archive's salt would mean re-wrapping it
     * for every export.
     */
    fun wrapDeviceKey(deviceKey: ByteArray, blockNonce: ByteArray, blockSalt: ByteArray): ByteArray =
        crypto.seal(key, blockNonce, deviceKey, blockKeyAad(blockSalt))

    /** Unwraps a device key, with the same authenticated data it was wrapped with. */
    fun unwrapDeviceKey(block: ArchiveKeyBlock): ByteArray =
        crypto.open(key, block.nonce, block.ciphertext, blockKeyAad(block.salt))

    /** What a change is sealed with: this archive's prefix, then the change's place. */
    private fun entryAad(prefix: ByteArray, index: Int): ByteArray = prefix + index.toBytes()

    companion object {

        /**
         * What a key block is authenticated with.
         *
         * A marker of its own and the block's own salt — never the archive's
         * prefix, because the block travels in every archive the device writes
         * and is wrapped once.
         */
        internal fun blockKeyAad(salt: ByteArray): ByteArray = KEY_BLOCK_AAD + salt

        /** The marker that says "this is a wrapped device key". */
        private val KEY_BLOCK_AAD = "SGBK-KEY-1".encodeToByteArray()

        /** A session whose key comes from the password. */
        fun forPassword(crypto: ArchiveCrypto, password: CharArray, salt: ByteArray): ArchiveCryptoSession =
            ArchiveCryptoSession(crypto, crypto.deriveKey(password, salt))

        /**
         * A session whose key comes from the device key.
         *
         * The device key is random bytes rather than text, so it is fed to the
         * derivation as its hexadecimal spelling: every byte survives the trip,
         * and the same bytes always spell the same way.
         */
        fun forDeviceKey(crypto: ArchiveCrypto, deviceKey: ByteArray, salt: ByteArray): ArchiveCryptoSession =
            ArchiveCryptoSession(crypto, crypto.deriveKey(deviceKey.asPasswordChars(), salt))
    }
}
