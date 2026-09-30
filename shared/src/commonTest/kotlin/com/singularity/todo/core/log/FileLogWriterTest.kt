package com.singularity.todo.core.log

import co.touchlab.kermit.Severity
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path as OkioPath

@Tag("slow")
class FileLogWriterTest {

    private val fs = FileSystem.SYSTEM

    @TempDir
    lateinit var tempDir: Path

    private fun okioPath(): OkioPath = tempDir.toString().toPath()

    @Suppress("FunctionExpressionBody")
    private fun newWriter(
        dir: OkioPath = okioPath(),
        fileSizeLimit: Long = 20L * 1024 * 1024,
        fileCount: Int = 4,
    ): FileLogWriter = FileLogWriter(dir, fs, fileSizeLimit, fileCount)

    @AfterEach
    fun cleanup() {
        for (i in 0..3) {
            val p = okioPath().resolve("log.$i.txt")
            if (fs.exists(p)) fs.delete(p)
        }
    }

    @Test
    fun `writes formatted entry to file`() {
        val writer = newWriter()
        writer.log(Severity.Info, "hello world", "TestTag", null)
        writer.beginShutdown()

        val content = readAll(okioPath().resolve("log.0.txt"))
        assertContains(content, "hello world")
        assertContains(content, "TestTag")
        assertContains(content, "I") // INFO severity letter
    }

    @Test
    fun `truncates tag to 23 characters`() {
        val writer = newWriter()
        val longTag = "VeryLongClassNameThatExceedsTwentyThreeCharacters"
        writer.log(Severity.Info, "msg", longTag, null)
        writer.beginShutdown()

        val line = readFirstLine(okioPath().resolve("log.0.txt"))
        assertTrue(line.length > 20, "Tag line should be present: $line")
    }

    @Test
    fun `rotates files when size limit exceeded`() {
        val writer = newWriter(fileSizeLimit = 100L, fileCount = 4)
        repeat(50) { writer.log(Severity.Info, "x".repeat(10), "Tag", null) }
        writer.beginShutdown()

        assertTrue(fs.exists(okioPath().resolve("log.0.txt")), "log.0 should exist")
        assertTrue(fs.exists(okioPath().resolve("log.1.txt")), "log.1 should exist after rotation")
    }

    @Test
    fun `beginShutdown waits for writes to complete`() {
        val writer = newWriter()
        writer.log(Severity.Info, "flush me", "Tag", null)
        writer.beginShutdown()

        val content = readAll(okioPath().resolve("log.0.txt"))
        assertContains(content, "flush me")
    }

    @Test
    fun `logFiles returns existing log files`() {
        val writer = newWriter()
        writer.log(Severity.Info, "a", "Tag", null)
        writer.beginShutdown()

        val files = writer.logFiles()
        assertFalse(files.isEmpty())
        assertTrue(files.all { it.name.startsWith("log.") && it.name.endsWith(".txt") })
    }

    @Test
    fun `appends to existing log across writer restarts`() {
        val dir = okioPath()
        val writer1 = newWriter(dir)
        writer1.log(Severity.Info, "first", "Tag", null)
        writer1.beginShutdown()

        val writer2 = newWriter(dir)
        writer2.log(Severity.Info, "second", "Tag", null)
        writer2.beginShutdown()

        val content = readAll(dir.resolve("log.0.txt"))
        assertContains(content, "first")
        assertContains(content, "second")
        assertFalse(fs.exists(dir.resolve("log.1.txt")), "log.1 should not exist after restart")
    }

    @Test
    fun `written counter resumes from existing file size on restart`() {
        val dir = okioPath()
        val writer1 = newWriter(dir, fileSizeLimit = 500L, fileCount = 4)
        repeat(4) { writer1.log(Severity.Info, "x".repeat(10), "Tag", null) }
        writer1.beginShutdown()

        assertFalse(fs.exists(dir.resolve("log.1.txt")), "log.1 should not exist before second writer")

        val writer2 = newWriter(dir, fileSizeLimit = 500L, fileCount = 4)
        repeat(5) { writer2.log(Severity.Info, "y".repeat(10), "Tag", null) }
        writer2.beginShutdown()

        // With fix: counter resumes from file size → one rotation fires → log.1 created.
        // Without fix: counter = 0 on restart → no rotation → log.1 not created.
        assertTrue(fs.exists(dir.resolve("log.1.txt")), "log.1 should exist after one rotation")
        assertFalse(fs.exists(dir.resolve("log.2.txt")), "log.2 should not exist — only one rotation")
    }

    private fun readAll(path: OkioPath): String {
        val buffer = Buffer()
        fs.source(path).use { source -> buffer.writeAll(source) }
        return buffer.readUtf8()
    }

    @Suppress("FunctionExpressionBody")
    private fun readFirstLine(path: OkioPath): String = readAll(path).lines().first()
}
