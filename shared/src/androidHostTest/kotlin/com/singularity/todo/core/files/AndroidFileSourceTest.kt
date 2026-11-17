package com.singularity.todo.core.files

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Platform [AndroidFileSource] must stream from local paths and content:// URIs.
 *
 * Robolectric is a JUnit4 runner discovered through Vintage; test methods return Unit.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidFileSourceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun openRead_streams_local_file_in_chunks_and_closes() {
        runTest {
            val bytes = ByteArray(64) { (it * 11 % 251).toByte() }
            val file = File(context.filesDir, "local-source.bin").apply { writeBytes(bytes) }

            val source = AndroidFileSource(context, file.absolutePath).openRead()
            val readBack = readAll(source, chunk = 5)

            assertTrue("file must be read in multiple chunks", readBack.reads > 1)
            assertArrayEquals(bytes, readBack.bytes)
            assertFailsWith<IOException> { source.read(ByteArray(1), 0, 1) }
        }
    }

    @Test
    fun openRead_streams_content_uri_and_closes_underlying_stream() {
        runTest {
            val bytes = "content-uri-bytes".encodeToByteArray()
            val uri = Uri.parse("content://test/source")
            val tracking = TrackingInputStream(bytes)
            shadowOf(context.contentResolver).registerInputStream(uri, tracking)

            val source = AndroidFileSource(context, uri.toString()).openRead()
            val readBack = readAll(source, chunk = 4)

            assertArrayEquals(bytes, readBack.bytes)
            assertTrue("closing ByteSource must close the underlying content stream", tracking.closed)
        }
    }

    @Test
    fun openRead_throws_when_content_resolver_returns_null_stream() {
        runTest {
            val uri = Uri.parse("content://test/missing")
            val error =
                assertFailsWith<FileNotFoundException> {
                    AndroidFileSource(context, uri.toString()).openRead()
                }
            assertEquals("Cannot open: $uri", error.message)
        }
    }

    private fun readAll(
        source: ByteSource,
        chunk: Int,
    ): ReadBack {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(chunk)
        var reads = 0
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n < 0) break
            if (n > 0) {
                reads++
                for (i in 0 until n) out += buffer[i]
            }
        }
        source.close()
        return ReadBack(out.toByteArray(), reads)
    }

    private data class ReadBack(val bytes: ByteArray, val reads: Int)

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
