package com.singularity.todo.core.auth.oauth

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }
