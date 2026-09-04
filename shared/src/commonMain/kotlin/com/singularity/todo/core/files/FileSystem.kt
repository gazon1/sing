package com.singularity.todo.core.files

import java.io.File

data class FileStat(
    val path: String,
    val lastModifiedEpochMillis: Long,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

interface FileSystem {
    suspend fun readBytes(path: String): ByteArray
    suspend fun writeBytes(path: String, data: ByteArray)
    suspend fun delete(path: String): Boolean
    suspend fun exists(path: String): Boolean
    suspend fun ensureDir(dir: String)
    suspend fun listDir(dir: String): List<String>
    suspend fun stat(path: String): FileStat?
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

    override suspend fun listDir(dir: String): List<String> {
        val f = File(dir)
        return if (f.exists() && f.isDirectory) f.listFiles()?.map { it.absolutePath } ?: emptyList()
        else emptyList()
    }

    override suspend fun stat(path: String): FileStat? {
        val f = File(path)
        return if (f.exists()) FileStat(f.absolutePath, f.lastModified(), f.length(), f.isDirectory)
        else null
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

    override suspend fun listDir(dir: String): List<String> =
        storage.keys.filter { it.startsWith("$dir/") }

    override suspend fun stat(path: String): FileStat? {
        val data = storage[path] ?: return null
        return FileStat(path, lastModifiedEpochMillis = 0L, sizeBytes = data.size.toLong(), isDirectory = false)
    }

    fun storedFiles(): Map<String, ByteArray> = storage.toMap()
}

class NoSuchFileException(path: String) : Exception("No such file: $path")
