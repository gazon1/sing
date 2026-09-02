package com.singularity.todo.core.files

import java.io.File
import kotlin.coroutines.cancellation.CancellationException

interface FileSystem {
    suspend fun readBytes(path: String): ByteArray
    suspend fun writeBytes(path: String, data: ByteArray)
    suspend fun delete(path: String): Boolean
    suspend fun exists(path: String): Boolean
    suspend fun ensureDir(dir: String)
}

class JvmFileSystem : FileSystem {
    override suspend fun readBytes(path: String): ByteArray =
        File(path).readBytes()

    override suspend fun writeBytes(path: String, data: ByteArray) {
        File(path).parentFile?.mkdirs()
        File(path).writeBytes(data)
    }

    override suspend fun delete(path: String): Boolean =
        File(path).delete()

    override suspend fun exists(path: String): Boolean =
        File(path).exists()

    override suspend fun ensureDir(dir: String) {
        File(dir).mkdirs()
    }
}

/**
 * In-memory fake for tests. Thread-safe via synchronized.
 */
class MapFileSystem(
    private val storage: MutableMap<String, ByteArray> = mutableMapOf(),
    private val dirs: MutableSet<String> = mutableSetOf()
) : FileSystem {
    override suspend fun readBytes(path: String): ByteArray =
        storage[path] ?: throw NoSuchFileException(path)

    override suspend fun writeBytes(path: String, data: ByteArray) {
        storage[path] = data
    }

    override suspend fun delete(path: String): Boolean =
        storage.remove(path) != null

    override suspend fun exists(path: String): Boolean =
        storage.containsKey(path)

    override suspend fun ensureDir(dir: String) {
        dirs.add(dir)
    }

    fun storedFiles(): Map<String, ByteArray> = storage.toMap()
}

class NoSuchFileException(path: String) : Exception("No such file: $path")
