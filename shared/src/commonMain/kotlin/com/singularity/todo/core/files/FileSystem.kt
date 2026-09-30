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
     * Lists the immediate children of `dir`, as **full paths** (the absolute path
     * of each child, not its bare name). Does not recurse.
     * Returns empty list if `dir` does not exist or is not a directory.
     */
    suspend fun listDir(dir: String): List<String>

    /** Returns `FileStat` for the path, or `null` if it does not exist. */
    suspend fun stat(path: String): FileStat?
}

/**
 * In-memory fake for tests.
 *
 * Directories live in [dirs], files in [storage], so every operation has to look
 * in both. Each method below originally consulted `storage` only, which silently
 * contradicted this file's own interface — `exists` returned `false` for a
 * directory it had just created, `delete` could not remove one, and `stat` never
 * reported `isDirectory = true`. Nothing caught it because the contract test's
 * `ensureDir is idempotent` case asserted nothing at all.
 *
 * Not thread-safe: the collections are plain ones and there is no `synchronized`
 * around them.
 */
class MapFileSystem(
    private val storage: MutableMap<String, ByteArray> = mutableMapOf(),
    private val dirs: MutableSet<String> = mutableSetOf(),
) : FileSystem {
    override suspend fun readBytes(path: String): ByteArray = storage[path] ?: throw NoSuchFileException(path)

    override suspend fun writeBytes(path: String, data: ByteArray) {
        storage[path] = data
    }

    override suspend fun delete(path: String): Boolean = storage.remove(path) != null || dirs.remove(path)

    override suspend fun exists(path: String): Boolean = storage.containsKey(path) || dirs.contains(path)

    override suspend fun ensureDir(dir: String) {
        dirs.add(dir)
    }

    /**
     * Immediate children, as full paths — the shape `JvmFileSystem` returns
     * (`File.listFiles().map { it.absolutePath }`). The interface KDoc used to
     * say "children", which read as bare names; both implementations and the
     * contract test have always returned full paths, so the doc was corrected
     * rather than the code.
     */
    override suspend fun listDir(dir: String): List<String> {
        val prefix = "${dir.trimEnd('/')}/"
        val children = LinkedHashSet<String>()
        storage.keys.forEach { key -> children += key }
        dirs.forEach { child -> children += child }
        return children.filter { it.startsWith(prefix) }.sorted()
    }

    override suspend fun stat(path: String): FileStat? {
        storage[path]?.let {
            return FileStat(path, lastModifiedEpochMillis = 0L, sizeBytes = it.size.toLong(), isDirectory = false)
        }
        if (path in dirs) {
            return FileStat(path, lastModifiedEpochMillis = 0L, sizeBytes = 0L, isDirectory = true)
        }
        return null
    }

    fun storedFiles(): Map<String, ByteArray> = storage.toMap()
}

class NoSuchFileException(path: String) : Exception("No such file: $path")
