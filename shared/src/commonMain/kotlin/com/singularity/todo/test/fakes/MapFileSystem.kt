package com.singularity.todo.test.fakes

import com.singularity.todo.core.files.FileStat
import com.singularity.todo.core.files.FileSystem

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
