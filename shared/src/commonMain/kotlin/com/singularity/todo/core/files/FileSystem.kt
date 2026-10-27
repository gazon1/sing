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
     * **Note**: on JVM this is not crash-safe — write a temporary file and
     * [move] it over the target when the data must survive a crash.
     */
    suspend fun writeBytes(path: String, data: ByteArray)

    /**
     * Moves [from] to [to], replacing [to] when it is there.
     *
     * The rename is what makes a write **atomic**: a file written under a
     * temporary name and then moved over the real one is either the old file or
     * the new one, never half of each other. A caller that writes the real name
     * directly can leave a truncated file behind when the process dies mid-write
     * — and the files where that matters are the bookkeeping ones, where a
     * truncated file is a lost chain.
     *
     * The move stays within one filesystem: a move across two is a copy, and a
     * copy is not atomic.
     */
    suspend fun move(from: String, to: String)

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

    /**
     * Opens the file at [path] for reading, in pieces.
     *
     * The counterpart of [readBytes] for the files that do not fit in memory: the
     * archive of an account with attachments is read this way, so that the app
     * does not have to hold the whole account to encrypt it.
     *
     * The read is blocking, like the platform's own streams — the caller is the
     * one that decides which thread it runs on.
     */
    fun openRead(path: String): ByteSource

    /**
     * Opens the file at [path] for writing, in pieces.
     *
     * The counterpart of [writeBytes]. The parent directory is created as needed,
     * the way [writeBytes] creates it.
     */
    fun openWrite(path: String): ByteSink
}
