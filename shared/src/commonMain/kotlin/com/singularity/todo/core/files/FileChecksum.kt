package com.singularity.todo.core.files

import java.security.MessageDigest

object FileChecksum {
    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }
}
