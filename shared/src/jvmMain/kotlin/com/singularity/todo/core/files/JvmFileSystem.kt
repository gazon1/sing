package com.singularity.todo.core.files

import java.io.File

/**
 * JVM platform implementation of [FileSystem] backed by [java.io.File].
 */
class JvmFileSystem : FileSystem {
    override suspend fun readBytes(path: String): ByteArray = File(path).readBytes()

    override suspend fun writeBytes(path: String, data: ByteArray) {
        File(path).parentFile?.mkdirs()
        File(path).writeBytes(data)
    }

    override suspend fun delete(path: String): Boolean = File(path).delete()

    override suspend fun exists(path: String): Boolean = File(path).exists()

    override suspend fun ensureDir(dir: String) {
        File(dir).mkdirs()
    }

    override suspend fun listDir(dir: String): List<String> {
        val f = File(dir)
        return if (f.exists() && f.isDirectory) {
            f.listFiles()?.map { it.absolutePath } ?: emptyList()
        } else {
            emptyList()
        }
    }

    override suspend fun stat(path: String): FileStat? {
        val f = File(path)
        return if (f.exists()) {
            FileStat(f.absolutePath, f.lastModified(), f.length(), f.isDirectory)
        } else {
            null
        }
    }
}
