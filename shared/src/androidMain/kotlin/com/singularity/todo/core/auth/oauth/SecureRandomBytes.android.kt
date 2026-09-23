package com.singularity.todo.core.auth.oauth

import java.security.SecureRandom

/**
 * Android/JVM implementation: generates cryptographically secure random bytes
 * using [SecureRandom].
 */
actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    SecureRandom.getInstance("NativePRNGNonBlocking").nextBytes(bytes)
    return bytes
}
