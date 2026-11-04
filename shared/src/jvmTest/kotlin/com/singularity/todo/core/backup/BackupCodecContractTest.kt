@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.backup

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
}
