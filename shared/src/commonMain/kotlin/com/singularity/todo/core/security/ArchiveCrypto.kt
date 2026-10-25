package com.singularity.todo.core.security

/**
 * The cryptography the encrypted backup archive needs, as the platform provides it.
 *
 * ## Why this is a port
 *
 * AES-GCM and PBKDF2 live in `javax.crypto`, and `commonMain` may not use `java.*`:
 * the module is shared with Android and with the desktop JVM, and the rule of this
 * repository is that platform libraries stay in the platform layer. What the header
 * of an archive looks like, which bytes are authenticated and in what order is a
 * decision of the project — and it lives above this port, where a test can check it.
 *
 * The implementations are `JvmArchiveCrypto` and `AndroidArchiveCrypto`; both use
 * the JDK's providers, and neither adds a dependency.
 */
interface ArchiveCrypto {

    /**
     * [count] bytes of unpredictable randomness.
     *
     * The salt and the nonce of an archive come from here. A nonce that repeats under
     * one key breaks GCM's security outright, and a salt that repeats makes two
     * archives under one password guessable together — so this cannot be
     * `kotlin.random.Random`, which is predictable by design.
     */
    fun randomBytes(count: Int): ByteArray

    /**
     * The key for [password] under [salt].
     *
     * The derivation is deliberately slow: a password is short and guessable, and the
     * cost is what makes guessing it expensive. The number of iterations is the
     * platform's, and it is named in the decision record that took this shape.
     */
    fun deriveKey(password: CharArray, salt: ByteArray): ByteArray

    /**
     * Encrypts [plaintext] under [key] with [nonce]; the result carries the GCM tag.
     *
     * [aad] is authenticated but not encrypted: it travels with the ciphertext and
     * cannot be changed without the tag failing.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * The inverse of [seal].
     *
     * Throws when the ciphertext, the tag or the authenticated data does not match —
     * which is what a wrong password looks like from here.
     */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
}
