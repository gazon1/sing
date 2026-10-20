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
    /**
     * Reads entire file into memory.
     *
     * A missing path raises the platform's own exception —
     * `java.io.FileNotFoundException` on both JVM and Android — because the port
     * does not wrap errors. The in-memory double throws its own
     * `NoSuchFileException` instead, and says so in its KDoc.
     */
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
