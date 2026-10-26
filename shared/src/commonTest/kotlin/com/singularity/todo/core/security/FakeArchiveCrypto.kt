package com.singularity.todo.core.security

import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource

/**
 * The archive cryptography, faked.
 *
 * ## Why a fake and not the real thing
 *
 * The real cryptography is a platform implementation: AES-GCM and PBKDF2 live in
 * `javax.crypto`, and `commonMain` may not use `java.*`. So the tests of the
 * *container* — the header, the salt, the nonce, the version, the authenticated
 * data — cannot reach it from `commonTest`.
 *
 * What the container needs from the cryptography is small and precise:
 *
 * - bytes that differ from call to call, so two archives under one password do
 *   not share a salt and a nonce;
 * - a key that depends on the password and on the salt;
 * - a seal that is not the plaintext, and an open that answers a wrong key with
 *   a failure rather than with garbage.
 *
 * This fake provides exactly that, and it is deliberately not a cipher: it is a
 * test double that keeps the *shape* of the real one. The round trip against the
 * real AES-GCM is proved by `JvmArchiveCryptoTest`, on the JVM.
 *
 * The randomness is a counter, not `kotlin.random`: a test that has to say "two
 * archives are not the same bytes" must not depend on a random number generator.
 */
internal class FakeArchiveCrypto : ArchiveCrypto {

    /** How many bytes the fake has handed out — the stand-in for the random source. */
    private var handed = 0

    /** The nonce the last seal was given. */
    var lastNonce: ByteArray? = null
        private set

    /** The authenticated data the last seal was given. */
    var lastAad: ByteArray? = null
        private set

    override fun randomBytes(count: Int): ByteArray =
        ByteArray(count) { index -> ((handed + index) % 251).toByte() }.also { handed += count }

    override fun deriveKey(password: CharArray, salt: ByteArray): ByteArray =
        ByteArray(password.size + salt.size) { index ->
            if (index < password.size) password[index].code.toByte() else salt[index - password.size]
        }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        lastNonce = nonce
        lastAad = aad
        return xor(plaintext, key) + tag(key, nonce, aad)
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (ciphertext.size < TAG_BYTES) throw IllegalStateException("the ciphertext is too short to be sealed")
        val body = ciphertext.copyOfRange(0, ciphertext.size - TAG_BYTES)
        val tag = ciphertext.copyOfRange(ciphertext.size - TAG_BYTES, ciphertext.size)
        if (!tag.contentEquals(tag(key, nonce, aad))) throw ArchiveTagMismatchException()
        return xor(body, key)
    }

    override fun seal(source: ByteSource, sink: ByteSink, key: ByteArray, nonce: ByteArray, aad: ByteArray) {
        val sealed = seal(key, nonce, source.readAll(), aad)
        sink.write(sealed, 0, sealed.size)
    }

    override fun open(source: ByteSource, sink: ByteSink, key: ByteArray, nonce: ByteArray, aad: ByteArray) {
        val plain = open(key, nonce, source.readAll(), aad)
        sink.write(plain, 0, plain.size)
    }

    /**
     * Everything the source yields.
     *
     * The fake holds the archive in memory on purpose: it is a test double, and
     * the memory behaviour of the real thing is not what these tests are about.
     */
    private fun ByteSource.readAll(): ByteArray {
        var collected = ByteArray(0)
        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read < 0) break
            collected += buffer.copyOfRange(0, read)
        }
        return collected
    }

    private fun xor(bytes: ByteArray, key: ByteArray): ByteArray =
        ByteArray(bytes.size) { index ->
            ((bytes[index].toLong() xor key[index % key.size].toLong()) and 0xFF).toByte()
        }

    /**
     * A tag that depends on the key, the nonce and the authenticated data.
     *
     * It is not a MAC and it is not secure — it only has to answer one question
     * honestly: *was this sealed with this key?* A wrong password has to fail
     * here, exactly as it fails on the GCM tag.
     */
    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        var acc = 7L
        for (byte in key) acc = acc * 31 + byte
        for (byte in nonce) acc = acc * 31 + byte
        for (byte in aad) acc = acc * 31 + byte
        return ByteArray(TAG_BYTES) { index -> ((acc shr (index * 8)) and 0xFF).toByte() }
    }

    private companion object {
        /**
         * The tag's length: the **real** GCM tag's length.
         *
         * The fake is a stand-in for the cipher, not for the cipher's *sizes*:
         * the container has a field whose length is fixed by the tag — the key
         * block of a version-3 container is salt, nonce, key and tag, and the
         * reader knows exactly how many bytes that is. A fake with a shorter
         * tag writes a block the reader reads wrong, and the failure looks like
         * a broken archive rather than a broken double.
         */
        const val TAG_BYTES = 16
        const val CHUNK = 1024
    }
}
