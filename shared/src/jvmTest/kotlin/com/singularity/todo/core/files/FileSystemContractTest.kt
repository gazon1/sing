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
 *
 * ## Why every path is built by [tmpPath] and not `System.nanoTime()`
 *
 * Every test here used to build its own path as `/tmp/contract-test-${System.nanoTime()}`,
 * on the assumption that the timestamp makes it unique. It does not, for two independent
 * reasons: `System.nanoTime()` has an arbitrary origin and is only *relatively* meaningful, so
 * two JVMs can read the same value, and — the one that actually bites here — JUnit is
 * configured to run test **methods within a class concurrently**, so two methods of this class
 * can read it in the same tick.
 *
 * Two tests then share a path, and one of them is `delete returns true and removes existing
 * file`. The other creates the file, this one deletes it, and the create fails with
 * `FileNotFoundException` — an error whose message names neither the cause nor the collision.
 * It reproduced under the full suite and passed in isolation, which is the signature of a
 * concurrency bug rather than of a broken filesystem.
 *
 * [tmpPath] combines a per-JVM unique id with a per-call counter, so no two calls anywhere can
 * produce the same string.
 */
@Tag("slow")
abstract class FileSystemContract<F : FileSystem>(private val makeSut: () -> F) {

    private val sut: F by lazy { makeSut() }

    private companion object {
        /** Unique per JVM: forkEvery = 1 gives each test class its own, but do not rely on it. */
        val RUN_ID: String = java.util.UUID.randomUUID().toString().take(8)

        val CALLS = java.util.concurrent.atomic.AtomicInteger(0)

        fun tmpPath(suffix: String = "f"): String = "/tmp/contract-test-$RUN_ID-${CALLS.incrementAndGet()}$suffix"

        fun missingPath(): String = "/tmp/contract-test-missing-$RUN_ID-${CALLS.incrementAndGet()}"
    }

    @Test
    fun `readBytes throws for missing path`() = runTest {
        val result = runCatching { sut.readBytes("/nonexistent/file") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `writeBytes and readBytes are round-trippable`() = runTest {
        val path = tmpPath()
        val data = "hello world".toByteArray()
        sut.writeBytes(path, data)
        assertContentEquals(data, sut.readBytes(path))
        sut.delete(path)
    }

    @Test
    fun `writeBytes creates parent directories`() = runTest {
        val path = tmpPath("/subdir/file.txt")
        val data = byteArrayOf(1, 2, 3)
        sut.writeBytes(path, data)
        assertTrue(sut.exists(path))
        sut.delete(path)
    }

    @Test
    fun `exists returns false for missing path`() = runTest {
        assertFalse(sut.exists(missingPath()))
    }

    @Test
    fun `exists returns true after writeBytes`() = runTest {
        val path = tmpPath()
        sut.writeBytes(path, byteArrayOf(0))
        assertTrue(sut.exists(path))
        sut.delete(path)
    }

    @Test
    fun `delete returns true and removes existing file`() = runTest {
        val path = tmpPath()
        sut.writeBytes(path, byteArrayOf(0))
        assertTrue(sut.delete(path))
        assertFalse(sut.exists(path))
    }

    @Test
    fun `delete returns false for missing path`() = runTest {
        assertFalse(sut.delete(missingPath()))
    }

    @Test
    fun `ensureDir is idempotent`() = runTest {
        val dir = tmpPath()
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
        assertTrue(sut.listDir(missingPath()).isEmpty())
    }

    @Test
    fun `listDir returns children after writeBytes`() = runTest {
        val dir = tmpPath()
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
        assertNull(sut.stat(missingPath()))
    }

    @Test
    fun `stat returns correct metadata after writeBytes`() = runTest {
        val path = tmpPath()
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
