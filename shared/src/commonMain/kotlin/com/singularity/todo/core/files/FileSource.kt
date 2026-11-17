package com.singularity.todo.core.files

/**
 * Platform-specific byte source for backup import.
 *
 * On JVM: reads from a local file path.
 * On Android: reads from either a local file path OR a `content://` URI
 * returned by the system file picker (FileKit resolves SAF URIs to content://).
 *
 * Use [FileSourceFactory] to construct an instance from a path/URI string.
 */
interface FileSource {
    /**
     * Reads all bytes from this source.
     * @throws java.io.FileNotFoundException if the source cannot be opened.
     */
    suspend fun readBytes(): ByteArray

    /**
     * Opens this source for streaming reads.
     *
     * The caller owns the returned [ByteSource] and must [ByteSource.close] it.
     * Prefer this over [readBytes] when the consumer can stream (e.g. zip decode),
     * to avoid materializing the whole source in memory.
     */
    suspend fun openRead(): ByteSource = ByteArraySource(readBytes())
}

/**
 * Factory for [FileSource], bound per-platform so [BackupImporter] (commonMain)
 * stays decoupled from platform details.
 */
interface FileSourceFactory {
    /**
     * Creates a [FileSource] from a path or URI string.
     *
     * On JVM: always treats [path] as a local file path.
     * On Android: if [path] starts with `content://`, opens it via
     * [android.content.ContentResolver.openInputStream];
     * otherwise treats it as a local path.
     */
    operator fun invoke(path: String): FileSource
}
