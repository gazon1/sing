package com.singularity.todo.feature.attachments.viewer

import com.singularity.todo.core.files.FileStat
import com.singularity.todo.core.files.FileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The size limit is enforced *before* the read, and this class is what makes that
 * order checkable.
 *
 * `FileSystem` has no streaming read — it returns whole files — so an implementation
 * that reads first and checks the length afterwards has already put the file on the
 * heap. The failure that prevents is an out-of-memory kill, which no test can observe
 * directly; what this test observes is the only proxy available, namely that
 * [FileSystem.readBytes] was never called.
 */
@Tag("fast")
class TextViewerLoadTest {

    /** A [FileSystem] that records whether it was asked to read anything. */
    private class RecordingFileSystem(
        private val stat: FileStat?,
        private val content: ByteArray = ByteArray(0),
        private val readFails: Boolean = false,
    ) : FileSystem {
        var readCalls: Int = 0
            private set

        override suspend fun readBytes(path: String): ByteArray {
            readCalls++
            if (readFails) error("boom")
            return content
        }

        override suspend fun stat(path: String): FileStat? = stat
        override suspend fun writeBytes(path: String, data: ByteArray) = Unit
        override suspend fun delete(path: String): Boolean = true
        override suspend fun exists(path: String): Boolean = stat != null
        override suspend fun ensureDir(dir: String) = Unit
        override suspend fun listDir(dir: String): List<String> = emptyList()
    }

    private fun statOf(size: Long, isDir: Boolean = false) =
        FileStat(path = "/tmp/a.txt", lastModifiedEpochMillis = 0, sizeBytes = size, isDirectory = isDir)

    @Test
    fun `a file within the limit is read and shown`() = runTest {
        val fs = RecordingFileSystem(statOf(1024), "hello".encodeToByteArray())
        val state = loadTextForViewer(fs, "/tmp/a.txt")
        assertIs<TextViewerState.Loaded>(state)
        assertEquals("hello", state.text)
        assertEquals(1, fs.readCalls)
    }

    @Test
    fun `a file over the limit is never read`() = runTest {
        val fs = RecordingFileSystem(statOf(MAX_IN_APP_TEXT_BYTES + 1))
        val state = loadTextForViewer(fs, "/tmp/a.txt")
        assertIs<TextViewerState.TooLarge>(state)
        assertEquals(0, fs.readCalls, "the file was read before the limit was checked")
    }

    @Test
    fun `a file many times the limit is still never read`() = runTest {
        // The realistic case: a 400 MB video that reached the text viewer because its
        // extension was not in the classifier's list.
        val fs = RecordingFileSystem(statOf(400L * 1024 * 1024))
        val state = loadTextForViewer(fs, "/tmp/big.mp4")
        assertIs<TextViewerState.TooLarge>(state)
        assertEquals(0, fs.readCalls)
    }

    @Test
    fun `a file exactly at the limit is read`() = runTest {
        // Boundary is inclusive; being off by one here would reject a legitimate file.
        val fs = RecordingFileSystem(statOf(MAX_IN_APP_TEXT_BYTES), "x".encodeToByteArray())
        val state = loadTextForViewer(fs, "/tmp/a.txt")
        assertIs<TextViewerState.Loaded>(state)
        assertEquals(1, fs.readCalls)
    }

    @Test
    fun `one byte over the limit is not read`() = runTest {
        val fs = RecordingFileSystem(statOf(MAX_IN_APP_TEXT_BYTES + 1))
        loadTextForViewer(fs, "/tmp/a.txt")
        assertEquals(0, fs.readCalls)
    }

    @Test
    fun `an empty file is shown as empty text`() = runTest {
        val fs = RecordingFileSystem(statOf(0), ByteArray(0))
        val state = loadTextForViewer(fs, "/tmp/empty.txt")
        assertIs<TextViewerState.Loaded>(state)
        assertEquals("", state.text)
    }

    @Test
    fun `a missing file is unavailable and not read`() = runTest {
        val fs = RecordingFileSystem(stat = null)
        val state = loadTextForViewer(fs, "/tmp/gone.txt")
        assertIs<TextViewerState.Unavailable>(state)
        assertEquals(0, fs.readCalls)
    }

    @Test
    fun `a directory is unavailable`() = runTest {
        val fs = RecordingFileSystem(statOf(100, isDir = true))
        assertIs<TextViewerState.Unavailable>(loadTextForViewer(fs, "/tmp/dir"))
        assertEquals(0, fs.readCalls)
    }

    @Test
    fun `a read failure is reported and not thrown`() = runTest {
        val fs = RecordingFileSystem(statOf(10), readFails = true)
        val state = loadTextForViewer(fs, "/tmp/a.txt")
        assertIs<TextViewerState.Failed>(state)
        assertTrue(state.reason.isNotBlank(), "a failure with no message leaves the user with nothing")
    }

    @Test
    fun `the limit is four megabytes`() {
        assertEquals(4L * 1024 * 1024, MAX_IN_APP_TEXT_BYTES)
    }
}
