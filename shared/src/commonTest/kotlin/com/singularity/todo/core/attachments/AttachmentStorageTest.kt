package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentStorageTest {

    @Test
    fun saveFileWritesDataToCorrectPath() = runTest {
        val fs = MapFileSystem()
        val storage = AttachmentStorage(fs, "/attachments")

        val taskId = "task1"
        val attId = "att_test123"
        val ext = "png"
        val sourceData = "test image data".toByteArray()

        // Write source file
        fs.writeBytes("/tmp/test.png", sourceData)

        val result = storage.saveFile(taskId, attId, "/tmp/test.png", ext)

        assertTrue(result.isSuccess)
        val path = result.getOrThrow()
        assertTrue(path.contains(taskId))
        assertTrue(path.contains(attId))
        assertTrue(path.endsWith(".png"))
    }

    @Test
    fun computeChecksumReturnsStableHash() = runTest {
        val fs = MapFileSystem()
        val storage = AttachmentStorage(fs, "/attachments")

        fs.writeBytes("/test/file.txt", "hello world".toByteArray())

        val checksum1 = storage.computeChecksum("/test/file.txt").getOrThrow()
        val checksum2 = storage.computeChecksum("/test/file.txt").getOrThrow()

        assertEquals(checksum1, checksum2)
        assertEquals(64, checksum1.length) // SHA-256 hex length
    }

    @Test
    fun computeChecksumFailsForMissingFile() = runTest {
        val fs = MapFileSystem()
        val storage = AttachmentStorage(fs, "/attachments")

        val result = storage.computeChecksum("/nonexistent/file.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun deleteFileRemovesFile() = runTest {
        val fs = MapFileSystem()
        val storage = AttachmentStorage(fs, "/attachments")

        fs.writeBytes("/test/delete.txt", "data".toByteArray())
        assertEquals(true, fs.exists("/test/delete.txt"))

        val result = storage.deleteFile("/test/delete.txt")
        assertTrue(result.isSuccess)
        assertEquals(false, fs.exists("/test/delete.txt"))
    }

    @Test
    fun fileExistsReturnsCorrectStatus() = runTest {
        val fs = MapFileSystem()
        val storage = AttachmentStorage(fs, "/attachments")

        fs.writeBytes("/test/exists.txt", "data".toByteArray())

        assertEquals(true, storage.fileExists("/test/exists.txt"))
        assertEquals(false, storage.fileExists("/test/missing.txt"))
    }
}
