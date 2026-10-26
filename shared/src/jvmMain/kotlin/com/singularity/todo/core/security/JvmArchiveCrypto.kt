package com.singularity.todo.core.security

import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The archive cryptography on the desktop JVM.
 *
 * The JDK's providers, and nothing else: AES-GCM and PBKDF2-HMAC-SHA256 are both in
 * `javax.crypto`, so the encrypted archive adds no dependency to the desktop build.
 *
 * The parameters are the ones [ArchiveCryptoParams] names, which the decision
 * record names too: PBKDF2 with 210 000 iterations and a 256-bit key, AES-GCM
 * with a 128-bit tag. They are read from there rather than written here, because
 * this file and [AndroidArchiveCrypto] are two copies of one implementation, and
 * a copy of a number is a number that drifts.
 */
class JvmArchiveCrypto : ArchiveCrypto {

    private val random = SecureRandom()

    override fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { random.nextBytes(it) }

    override fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ArchiveCryptoParams.ITERATIONS, ArchiveCryptoParams.KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ArchiveCryptoParams.KDF).generateSecret(spec).encoded
        } finally {
            // The password is the caller's memory of a secret; the spec holds a copy
            // of it in a byte array that cannot be wiped by assignment.
            spec.clearPassword()
        }
    }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ArchiveCryptoParams.TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(ArchiveCryptoParams.TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ArchiveCryptoParams.TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(ArchiveCryptoParams.TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            // The tag does not match: the password is wrong, or the bytes were
            // tampered with. The project's own failure says which word to use.
            throw ArchiveTagMismatchException(error)
        }
    }

    override fun seal(source: ByteSource, sink: ByteSink, key: ByteArray, nonce: ByteArray, aad: ByteArray) {
        val cipher = encrypting(key, nonce, aad)
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) break
            write(sink, cipher.update(buffer, 0, read))
        }
        // The tag can only be computed now, when the source has ended.
        write(sink, cipher.doFinal())
    }

    override fun open(source: ByteSource, sink: ByteSink, key: ByteArray, nonce: ByteArray, aad: ByteArray) {
        val cipher = decrypting(key, nonce, aad)
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) break
            write(sink, cipher.update(buffer, 0, read))
        }
        // The tag is checked here, at the end — which is why the sink may already
        // hold bytes when the password was wrong.
        try {
            write(sink, cipher.doFinal())
        } catch (error: AEADBadTagException) {
            throw ArchiveTagMismatchException(error)
        }
    }

    private fun encrypting(key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(ArchiveCryptoParams.TRANSFORM).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(ArchiveCryptoParams.TAG_BITS, nonce),
            )
            updateAAD(aad)
        }

    private fun decrypting(key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(ArchiveCryptoParams.TRANSFORM).apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(ArchiveCryptoParams.TAG_BITS, nonce),
            )
            updateAAD(aad)
        }

    private fun write(sink: ByteSink, bytes: ByteArray) {
        if (bytes.isNotEmpty()) sink.write(bytes, 0, bytes.size)
    }

    private companion object {
        /** How much of the archive is held in memory at a time. */
        const val CHUNK_BYTES = 64 * 1024
    }
}
