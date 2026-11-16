@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.files

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("fast")
class JvmFileSourceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `openRead streams file in chunks reaches eof and closes`() =
        runTest {
            val bytes = ByteArray(64) { (it * 7 % 251).toByte() }
            val file = tempDir.resolve("sample.bin")
            file.writeBytes(bytes)

            val source = JvmFileSource(file.toString()).openRead()
            val out = ArrayList<Byte>()
            val buffer = ByteArray(7)
            var reads = 0
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) break
                assertTrue(n <= buffer.size, "read must not exceed requested length")
                if (n == 0) continue
                reads++
                for (i in 0 until n) out += buffer[i]
            }

            assertTrue(reads > 1, "file must be read in multiple chunks")
            assertContentEquals(bytes, out.toByteArray())

            source.close()
            assertFailsWith<IOException> { source.read(ByteArray(1), 0, 1) }
        }
}
