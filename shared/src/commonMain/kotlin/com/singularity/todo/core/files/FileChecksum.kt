package com.singularity.todo.core.files

import okio.Buffer

/**
 * SHA-256, as lowercase hex.
 *
 * ## Why okio and not `java.security.MessageDigest`
 *
 * This object lives in `commonMain` and used to import `java.security.MessageDigest`,
 * which does not exist on Kotlin/Native, JS or Wasm. It compiled and passed its tests
 * because the only targets that ever compile it are JVM ones — a portability bug that
 * stays invisible until someone adds a target, and then surfaces as a compile error
 * on a platform the author never had.
 *
 * okio is already a dependency, is multiplatform, and gives the same digest in one
 * line. The callers — attachment checksums and backup integrity — sit on the file
 * upload path, so this also drops a `getInstance` lookup per call.
 *
 * `ByteString.hex()` returns lowercase hex, which is the exact encoding the old
 * `"%02x".format(...)` produced. That formatter is a JVM API too, so the obvious
 * in-place fix would have moved the same portability bug one level down.
 */
object FileChecksum {
    fun sha256(data: ByteArray): String = Buffer().write(data).sha256().hex()
}
