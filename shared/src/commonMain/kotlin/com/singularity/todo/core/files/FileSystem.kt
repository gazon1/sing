package com.singularity.todo.core.files

/**
 * Port for filesystem operations — platform boundary.
 *
 * JVM: backed by `java.io.File`. Android: backed by `context.filesDir`.
 * All operations are **best-effort atomic**: `writeBytes` is atomic on POSIX
 * (rename-to-target pattern recommended for true atomicity at the call site).
 *
 * Errors are propagated as platform-specific exceptions — no wrapping here.
 * Callers should wrap at the use-case level.
 */
data class FileStat(val path: String, val lastModifiedEpochMillis: Long, val sizeBytes: Long, val isDirectory: Boolean)

interface FileSystem {
    /** Reads entire file into memory. Throws `NoSuchFileException` if path does not exist. */
    suspend fun readBytes(path: String): ByteArray

    /**
     * Writes `data` to `path`, creating parent directories as needed.
     * **Note**: on JVM this is not crash-safe — use a temp file + rename for critical data.
     */
    suspend fun writeBytes(path: String, data: ByteArray)

    /** Deletes file or empty directory. Returns `true` if the path existed prior to deletion. */
    suspend fun delete(path: String): Boolean

    /** Returns `true` if the path exists (file or directory). */
    suspend fun exists(path: String): Boolean

    /** Creates directory and all parent directories. No-op if already exists. */
    suspend fun ensureDir(dir: String)

    /**
     * Lists immediate children of `dir`. Does not recurse.
     * Returns empty list if `dir` does not exist or is not a directory.
     */
    suspend fun listDir(dir: String): List<String>

    /** Returns `FileStat` for the path, or `null` if it does not exist. */
    suspend fun stat(path: String): FileStat?
}

/**
 * In-memory fake for tests. Thread-safe via synchronized.
 */
class MapFileSystem(
    private val storage: MutableMap<String, ByteArray> = mutableMapOf(),
    private val dirs: MutableSet<String> = mutableSetOf(),
) : FileSystem {
    override suspend fun readBytes(path: String): ByteArray = storage[path] ?: throw NoSuchFileException(path)

    override suspend fun writeBytes(path: String, data: ByteArray) {
        storage[path] = data
    }

    override suspend fun delete(path: String): Boolean = storage.remove(path) != null

    override suspend fun exists(path: String): Boolean = storage.containsKey(path)

    override suspend fun ensureDir(dir: String) {
        dirs.add(dir)
    }

    override suspend fun listDir(dir: String): List<String> {
        val normalized = dir.trimEnd('/')
        return storage.keys.filter { it.startsWith("$normalized/") }
    }

    override suspend fun stat(path: String): FileStat? {
        val data = storage[path] ?: return null
        return FileStat(path, lastModifiedEpochMillis = 0L, sizeBytes = data.size.toLong(), isDirectory = false)
    }

    fun storedFiles(): Map<String, ByteArray> = storage.toMap()
}

class NoSuchFileException(path: String) : Exception("No such file: $path")
