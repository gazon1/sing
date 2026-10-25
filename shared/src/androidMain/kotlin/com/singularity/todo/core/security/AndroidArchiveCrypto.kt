package com.singularity.todo.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The archive cryptography on Android.
 *
 * The same JDK providers the desktop uses — Android ships them — so the encrypted
 * archive is written by one implementation and read by another, byte for byte.
 *
 * The parameters are the ones [ArchiveCryptoParams] names, which the decision
 * record names too: PBKDF2 with 210 000 iterations and a 256-bit key, AES-GCM
 * with a 128-bit tag. `PBKDF2WithHmacSHA256` is available from API 26, and this
 * application does not go below it.
 *
 * They are read from there rather than written here, because this file and
 * [JvmArchiveCrypto] are two copies of one implementation, and a copy of a
 * number is a number that drifts: an archive written by one platform would stop
 * opening on the other, and the message would be "the password does not open
 * it". The golden test is what keeps the two halves equal.
 */
class AndroidArchiveCrypto : ArchiveCrypto {

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
        return cipher.doFinal(ciphertext)
    }
}
