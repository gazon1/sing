package com.singularity.todo.test.fakes

import com.singularity.todo.core.backup.BackupBucket
import com.singularity.todo.core.backup.BucketObject
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource

/**
 * A bucket that keeps its objects in memory.
 *
 * The backup bucket's contract in one map: an upload replaces whatever was at
 * the path, a download answers with the bytes that were stored, and a listing is
 * scoped to the prefix it was asked for.
 *
 * A missing object is an answer in the bucket's own language — the failure kind
 * the layers above already know how to classify — not a crash, so a test that
 * restores an archive that is not there hears "not found" rather than an
 * unclassified error.
 */
class FakeBackupBucket : BackupBucket {
    private val objects = mutableMapOf<String, ByteArray>()

    /** What the bucket answers with, when a test arms it to fail. */
    var failure: Throwable? = null

    override suspend fun upload(
        path: String,
        source: ByteSource,
        sizeBytes: Long,
    ) {
        failure?.let { throw it }
        val bytes = readAll(source)
        if (bytes.size.toLong() != sizeBytes) {
            // The caller promised a size and delivered another. A bucket that
            // stored it anyway would keep the mistake until somebody opened the
            // archive and found it short, so the write is refused here instead.
            throw TransportException(
                TransportFailure.REJECTED,
                "the archive at $path was promised as $sizeBytes bytes and ${bytes.size} arrived",
            )
        }
        objects[path] = bytes
    }

    override suspend fun download(
        path: String,
        sink: ByteSink,
    ) {
        failure?.let { throw it }
        val bytes = objects[path] ?: throw TransportException(TransportFailure.MISSING, "no object at $path")
        sink.write(bytes, 0, bytes.size)
    }

    override suspend fun list(prefix: String): List<BucketObject> {
        failure?.let { throw it }
        return objects.keys
            .filter { it.startsWith(prefix) }
            .sorted()
            .map { key ->
                BucketObject(
                    path = key,
                    updatedAtEpochMillis = 0L,
                    sizeBytes = objects.getValue(key).size.toLong(),
                )
            }
    }

    /** Everything the source yields, read in pieces. */
    private fun readAll(source: ByteSource): ByteArray {
        var collected = ByteArray(0)
        val buffer = ByteArray(1024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) break
            collected += buffer.copyOfRange(0, read)
        }
        return collected
    }
}
