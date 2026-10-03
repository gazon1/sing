@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.backup

import com.singularity.todo.core.files.MapFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupCodecTest {

    @Test
    fun `export and import roundtrip preserves data`() = runTest {
        val codec = JvmBackupCodec()
        val fs = MapFileSystem()

        val manifestBytes = """{"formatVersion":1,"appName":"test","appVersion":"1.0","createdAtEpochMillis":1000,"userIdHash":"abc","schemaVersion":1,"entityCounts":{},"payloadChecksum":"def"}""".toByteArray()
        val payloadBytes = """{"schemaVersion":1,"tasks":[]}""".toByteArray()
        val attachments = listOf("file1.txt" to "hello".toByteArray())

        val destPath = "/backup/test.zip"
        codec.export(manifestBytes, payloadBytes, attachments, destPath, fs).getOrThrow()

        assertTrue(fs.exists(destPath))

        val result = codec.import(destPath, fs).getOrThrow()

        assertEquals(manifestBytes.toList(), result.manifestBytes.toList())
        assertEquals(payloadBytes.toList(), result.payloadBytes.toList())
        assertEquals("hello", result.attachments["file1.txt"]?.decodeToString())
    }

    @Test
    fun `import fails for nonexistent file`() = runTest {
        val codec = JvmBackupCodec()
        val fs = MapFileSystem()

        val result = codec.import("/nonexistent.zip", fs)
        assertTrue(result.isFailure)
        result.onFailure {
            assertTrue(it is BackupError.FileNotFound)
        }
    }

    @Test
    fun `export with empty attachments`() = runTest {
        val codec = JvmBackupCodec()
        val fs = MapFileSystem()

        val manifestBytes = "{}".toByteArray()
        val payloadBytes = "{}".toByteArray()

        codec.export(manifestBytes, payloadBytes, emptyList(), "/backup/empty.zip", fs).getOrThrow()

        val result = codec.import("/backup/empty.zip", fs).getOrThrow()
        assertTrue(result.attachments.isEmpty())
    }
}
