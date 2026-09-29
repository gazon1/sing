package com.singularity.todo.core.files

import java.io.File

/**
 * JVM [FileSource]: reads from a local file path.
 * No URI handling needed — JVM has no system file picker returning content://.
 */
class JvmFileSource(private val path: String) : FileSource {
    override suspend fun readBytes(): ByteArray = File(path).readBytes()
}

/**
 * JVM [FileSourceFactory]: returns a [JvmFileSource] for the given path.
 */
class JvmFileSourceFactory : FileSourceFactory {
    override operator fun invoke(path: String): FileSource = JvmFileSource(path)
}
