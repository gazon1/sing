@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract tests for the [BackupCodec] port.
 *
 * Both [JvmBackupCodec] and [AndroidBackupCodec] use identical java.util.zip code —
 * testing one validates both. An [androidHostTest] variant should be added when
 * the Android implementation diverges.
 *
 * Uses [MapFileSystem] as the I/O backend so tests run without filesystem side effects.
 */
@Tag("fast")
class BackupCodecContractTest {
    private val fs: FileSystem = MapFileSystem()
    private val sut = JvmBackupCodec()
    private val backupPath = "/backup/test.zip"

    @Test
    fun `export and import are lossless round-trip`() =
        runTest {
            val manifest = """{"version":1,"schema":1}""".toByteArray()
            val payload = """{"tasks":[]}""".toByteArray()
            fs.writeBytes("/attach/note-1.md", "Hello world".toByteArray())
            fs.writeBytes("/attach/note-2.md", "# Title".toByteArray())
            val attachments =
                listOf(
                    "note-1.md" to "/attach/note-1.md",
                    "note-2.md" to "/attach/note-2.md",
                )

            sut.export(manifest, payload, attachments, backupPath, fs)
            val result = sut.import(backupPath, fs)

            assertTrue(result.isSuccess)
            val read = result.getOrThrow()

            assertContentEquals(manifest, read.manifestBytes)
            assertContentEquals(payload, read.payloadBytes)
            assertEquals(2, read.attachments.size)
            assertContentEquals("Hello world".toByteArray(), read.attachments["note-1.md"])
            assertContentEquals("# Title".toByteArray(), read.attachments["note-2.md"])
            fs.delete(backupPath)
        }

    @Test
    fun `import fails with FileNotFound for missing path`() =
        runTest {
            val result = sut.import("/nonexistent/${System.nanoTime()}.zip", fs)

            assertTrue(result.isFailure)
            assertTrue { result.exceptionOrNull() is BackupStorageError.FileNotFound }
        }

    @Test
    fun `export with no attachments still produces valid zip`() =
        runTest {
            val manifest = """{"v":1}""".toByteArray()
            val payload = """{}""".toByteArray()

            sut.export(manifest, payload, emptyList(), backupPath, fs)
            val result = sut.import(backupPath, fs)

            assertTrue(result.isSuccess)
            assertContentEquals(manifest, result.getOrThrow().manifestBytes)
            assertContentEquals(payload, result.getOrThrow().payloadBytes)
            assertTrue(result.getOrThrow().attachments.isEmpty())
            fs.delete(backupPath)
        }

    @Test
    fun `import fails with MalformedManifest when manifest entry is missing`() =
        runTest {
            // An archive whose manifest entry was lost: there is nothing in it
            // that says what the payload contains, so the import must refuse it.
            fs.writeBytes(backupPath, zipOf(BackupFormat.ENTRY_PAYLOAD to """{"tasks":[]}""".toByteArray()))

            val result = sut.import(backupPath, fs)

            assertTrue(result.isFailure)
            val error = assertIs<BackupPayloadError.MalformedManifest>(result.exceptionOrNull())
            assertContains(error.message.orEmpty(), "missing manifest.json")
            fs.delete(backupPath)
        }

    @Test
    fun `import fails with MalformedManifest when payload entry is missing`() =
        runTest {
            fs.writeBytes(backupPath, zipOf(BackupFormat.ENTRY_MANIFEST to """{"v":1}""".toByteArray()))

            val result = sut.import(backupPath, fs)

            assertTrue(result.isFailure)
            val error = assertIs<BackupPayloadError.MalformedManifest>(result.exceptionOrNull())
            assertContains(error.message.orEmpty(), "missing payload.json")
            fs.delete(backupPath)
        }

    @Test
    fun `CodecReadResult manifest and payload are preserved after round-trip`() =
        runTest {
            val manifest = """{"v":1}""".toByteArray()
            val payload = """{"tasks":[]}""".toByteArray()

            sut.export(manifest, payload, emptyList(), backupPath, fs)
            val result = sut.import(backupPath, fs)

            assertTrue(result.isSuccess)
            assertContentEquals(manifest, result.getOrThrow().manifestBytes)
            assertContentEquals(payload, result.getOrThrow().payloadBytes)
            fs.delete(backupPath)
        }

    @Test
    fun `importFromSource streams without buffering the archive and reads in small chunks`() =
        runTest {
            val manifest = """{"v":1}""".toByteArray()
            val payload = """{"tasks":[]}""".toByteArray()
            sut.export(manifest, payload, emptyList(), backupPath, fs)
            val zipBytes = fs.readBytes(backupPath)

            val source = TrackingFileSource(zipBytes, maxChunk = 5)

            val result = sut.importFromSource(source)

            assertTrue(result.isSuccess)
            val read = result.getOrThrow()
            assertContentEquals(manifest, read.manifestBytes)
            assertContentEquals(payload, read.payloadBytes)
            assertEquals(1, source.opened, "the source must be opened exactly once")
            val input = source.lastOpened ?: error("source was never opened")
            assertTrue(input.readCalls > 1, "zip decode must consume the source in multiple reads")
            assertEquals(1, input.closeCalls, "the source must be closed exactly once on success")
            fs.delete(backupPath)
        }

    @Test
    fun `importFromSource reports truncated input from a streaming source`() =
        runTest {
            val manifest = """{"v":1}""".toByteArray()
            val payload = """{"tasks":[]}""".toByteArray()
            sut.export(manifest, payload, emptyList(), backupPath, fs)
            val truncated = fs.readBytes(backupPath).let { it.copyOf(it.size / 2) }

            val input = ChunkedByteSource(truncated, maxChunk = 4)
            val source =
                object : FileSource {
                    override suspend fun readBytes(): ByteArray = error("must stream")

                    override suspend fun openRead(): ByteSource = input
                }

            val result = sut.importFromSource(source)

            assertTrue(result.isFailure, "truncated archive must fail")
            assertEquals(1, input.closeCalls, "the source must be closed exactly once on decode error")
            fs.delete(backupPath)
        }

    /**
     * A zip holding exactly the given entries.
     *
     * The codec refuses an archive whose manifest or payload entry is missing,
     * and the exporter never writes such an archive — so the broken archive has
     * to be built here, by hand, for the refusal to be observable.
     */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private class TrackingFileSource(private val bytes: ByteArray, private val maxChunk: Int) : FileSource {
        var opened = 0
        var lastOpened: ChunkedByteSource? = null

        override suspend fun readBytes(): ByteArray =
            error("importFromSource must stream, not buffer the whole archive")

        override suspend fun openRead(): ByteSource {
            opened++
            if (opened > 1) error("source is non-repeatable")
            return ChunkedByteSource(bytes, maxChunk).also { lastOpened = it }
        }
    }

    private class ChunkedByteSource(private val bytes: ByteArray, private val maxChunk: Int) : ByteSource {
        private var position = 0
        var readCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= bytes.size) return -1
            readCalls++
            val count = minOf(length, maxChunk.coerceAtLeast(1), bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun close() {
            closeCalls++
        }
    }
}
