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
 * The parameters are the ones the decision record names: PBKDF2 with 210 000
 * iterations and a 256-bit key, AES-GCM with a 128-bit tag. `PBKDF2WithHmacSHA256`
 * is available from API 26, and this application does not go below it.
 */
class AndroidArchiveCrypto : ArchiveCrypto {

    private val random = SecureRandom()

    override fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { random.nextBytes(it) }

    override fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            // The password is the caller's memory of a secret; the spec holds a copy
            // of it in a byte array that cannot be wiped by assignment.
            spec.clearPassword()
        }
    }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KDF = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
