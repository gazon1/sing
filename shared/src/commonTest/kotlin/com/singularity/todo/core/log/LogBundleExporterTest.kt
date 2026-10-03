@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.log

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [LogBundleExporter].
 * Uses [MapFileSystem] as the I/O backend so tests run without filesystem side effects.
 *
 * Covers:
 * - Empty directory produces empty archive
 * - Single log file is included
 * - Multiple log files are included in order
 * - Archive path is returned on success
 * - Failure from BackupCodec propagates as Result.failure
 */
@Tag("slow")
class LogBundleExporterTest {

    private val fs: FileSystem = MapFileSystem()

    @Test
    fun `export with no log files produces empty archive`() = runTest {
        val codec = FakeBackupCodec()
        val sut = LogBundleExporter("/logs", codec, fs)

        val result = sut.export()

        assertTrue(result.isSuccess)
        assertEquals(1, codec.calls)
        assertTrue(codec.lastAttachments!!.isEmpty())
    }

    @Test
    fun `export with single log file includes it in attachments`() = runTest {
        fs.writeBytes("/logs/log.0.txt", "line1\nline2\n".toByteArray())
        val codec = FakeBackupCodec()
        val sut = LogBundleExporter("/logs", codec, fs)

        val result = sut.export()

        assertTrue(result.isSuccess)
        val attachments = codec.lastAttachments!!
        assertEquals(1, attachments.size)
        assertEquals("log.0.txt", attachments[0].first)
        assertEquals("line1\nline2\n".toByteArray().toList(), attachments[0].second.toList())
    }

    @Test
    fun `export with multiple log files includes all in order`() = runTest {
        fs.writeBytes("/logs/log.0.txt", "oldest".toByteArray())
        fs.writeBytes("/logs/log.1.txt", "middle".toByteArray())
        fs.writeBytes("/logs/log.2.txt", "newest".toByteArray())
        val codec = FakeBackupCodec()
        val sut = LogBundleExporter("/logs", codec, fs, fileCount = 3)

        val result = sut.export()

        assertTrue(result.isSuccess)
        val attachments = codec.lastAttachments!!
        assertEquals(3, attachments.size)
        assertEquals("log.0.txt", attachments[0].first)
        assertEquals("oldest".toByteArray().toList(), attachments[0].second.toList())
        assertEquals("log.1.txt", attachments[1].first)
        assertEquals("log.2.txt", attachments[2].first)
    }

    @Test
    fun `export skips missing log files`() = runTest {
        // log.0 exists but log.1 does not
        fs.writeBytes("/logs/log.0.txt", "only-one".toByteArray())
        val codec = FakeBackupCodec()
        val sut = LogBundleExporter("/logs", codec, fs, fileCount = 3)

        val result = sut.export()

        assertTrue(result.isSuccess)
        assertEquals(1, codec.lastAttachments!!.size)
        assertEquals("log.0.txt", codec.lastAttachments!![0].first)
    }

    @Test
    fun `export returns failure when BackupCodec fails`() = runTest {
        fs.writeBytes("/logs/log.0.txt", "content".toByteArray())
        val codec = FakeBackupCodec(failExport = true)
        val sut = LogBundleExporter("/logs", codec, fs)

        val result = sut.export()

        assertTrue(result.isFailure)
    }
}

/**
 * Minimal fake [BackupCodec] for testing [LogBundleExporter].
 */
private class FakeBackupCodec(
    private val failExport: Boolean = false,
) : BackupCodec {
    var calls = 0
        private set
    var lastAttachments: List<Pair<String, ByteArray>>? = null
        private set

    override suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, ByteArray>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit> {
        calls++
        lastAttachments = attachments
        return if (failExport) {
            Result.failure(IllegalStateException("synthetic export failure"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun import(sourcePath: String, fs: FileSystem): Result<BackupCodec.CodecReadResult> {
        error("not used in LogBundleExporter tests")
    }

    override suspend fun importFromSource(source: com.singularity.todo.core.files.FileSource): Result<BackupCodec.CodecReadResult> {
        error("not used in LogBundleExporter tests")
    }
}
