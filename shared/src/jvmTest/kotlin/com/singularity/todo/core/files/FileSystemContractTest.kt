@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.files

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the [FileSystem] port.
 *
 * Run against [JvmFileSystem] (real I/O) and [MapFileSystem] (in-memory).
 * The two implementations must agree on the semantics tested here.
 */
abstract class FileSystemContract<F : FileSystem>(private val makeSut: () -> F) {

    private val sut: F by lazy { makeSut() }

    @Test
    fun `readBytes throws for missing path`() = runTest {
        val result = runCatching { sut.readBytes("/nonexistent/file") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `writeBytes and readBytes are round-trippable`() = runTest {
        val path = "/tmp/contract-test-${System.nanoTime()}"
        val data = "hello world".toByteArray()
        sut.writeBytes(path, data)
        assertContentEquals(data, sut.readBytes(path))
        sut.delete(path)
    }

    @Test
    fun `writeBytes creates parent directories`() = runTest {
        val path = "/tmp/contract-test-${System.nanoTime()}/subdir/file.txt"
        val data = byteArrayOf(1, 2, 3)
        sut.writeBytes(path, data)
        assertTrue(sut.exists(path))
        sut.delete(path)
    }

    @Test
    fun `exists returns false for missing path`() = runTest {
        assertFalse(sut.exists("/nonexistent-${System.nanoTime()}"))
    }

    @Test
    fun `exists returns true after writeBytes`() = runTest {
        val path = "/tmp/contract-test-${System.nanoTime()}"
        sut.writeBytes(path, byteArrayOf(0))
        assertTrue(sut.exists(path))
        sut.delete(path)
    }

    @Test
    fun `delete returns true and removes existing file`() = runTest {
        val path = "/tmp/contract-test-${System.nanoTime()}"
        sut.writeBytes(path, byteArrayOf(0))
        assertTrue(sut.delete(path))
        assertFalse(sut.exists(path))
    }

    @Test
    fun `delete returns false for missing path`() = runTest {
        assertFalse(sut.delete("/nonexistent-${System.nanoTime()}"))
    }

    @Test
    fun `ensureDir is idempotent`() = runTest {
        val dir = "/tmp/contract-test-${System.nanoTime()}"
        // Asserting after *both* calls is what makes this a test of idempotence
        // rather than of "ensureDir creates a directory". Without the assertion
        // it passed even if ensureDir were an empty function, because nothing in
        // the test observed the result.
        sut.ensureDir(dir)
        sut.ensureDir(dir)
        assertTrue(sut.exists(dir), "ensureDir did not leave the directory behind")
    }

    @Test
    fun `listDir returns empty for nonexistent dir`() = runTest {
        assertTrue(sut.listDir("/nonexistent-${System.nanoTime()}").isEmpty())
    }

    @Test
    fun `listDir returns children after writeBytes`() = runTest {
        val dir = "/tmp/contract-test-${System.nanoTime()}"
        val fileA = "$dir/a.txt"
        val fileB = "$dir/b.txt"
        sut.writeBytes(fileA, byteArrayOf(1))
        sut.writeBytes(fileB, byteArrayOf(2))
        val children = sut.listDir("$dir/")
        assertEquals(2, children.size)
        assertTrue(children.contains(fileA))
        assertTrue(children.contains(fileB))
        sut.delete(fileA)
        sut.delete(fileB)
    }

    @Test
    fun `stat returns null for missing path`() = runTest {
        assertNull(sut.stat("/nonexistent-${System.nanoTime()}"))
    }

    @Test
    fun `stat returns correct metadata after writeBytes`() = runTest {
        val path = "/tmp/contract-test-${System.nanoTime()}"
        val data = "metadata test".toByteArray()
        sut.writeBytes(path, data)
        val stat = sut.stat(path)
        assertNotNull(stat)
        assertEquals(path, stat.path)
        assertEquals(data.size.toLong(), stat.sizeBytes)
        assertFalse(stat.isDirectory)
        sut.delete(path)
    }
}

/** Contract test run against [JvmFileSystem] — real filesystem I/O. */
@Tag("slow")
class JvmFileSystemContractTest : FileSystemContract<JvmFileSystem>(::JvmFileSystem)

/** Contract test run against [MapFileSystem] — in-memory. */
@Tag("slow")
class MapFileSystemContractTest : FileSystemContract<MapFileSystem>(::MapFileSystem)
