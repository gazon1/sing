package com.singularity.todo.core.log

import co.touchlab.kermit.Severity
import okio.Buffer
import okio.FileSystem
import okio.Path
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun Path.Companion.make(raw: String): Path {
    val method = okio.Path::class.java.getMethod("get", String::class.java)
    @Suppress("UNCHECKED_CAST")
    return method.invoke(null, raw) as Path
}

@Tag("slow")
class FileLogWriterTest {

    private val fs = FileSystem.SYSTEM
    private val baseDir: Path = Path.make(System.getProperty("java.io.tmpdir")!!)

    private fun testDir(name: String) = baseDir.resolve("file-log-writer-test").resolve(name)

    private fun newWriter(dir: Path) = FileLogWriter(dir, fs)

    @Test
    fun `writes formatted entry to file`() {
        val dir = testDir("test1")
        val writer = newWriter(dir)
        writer.log(Severity.Info, "hello world", "TestTag", null)
        writer.beginShutdown()

        val content = readAll(dir.resolve("log.0.txt"))
        assertContains(content, "hello world")
        assertContains(content, "TestTag")
        assertContains(content, "I") // INFO severity letter
    }

    @Test
    fun `truncates tag to 23 characters`() {
        val dir = testDir("test2")
        val longTag = "VeryLongClassNameThatExceedsTwentyThreeCharacters"
        val writer = newWriter(dir)
        writer.log(Severity.Info, "msg", longTag, null)
        writer.beginShutdown()

        val line = readFirstLine(dir.resolve("log.0.txt"))
        assertTrue(line.length > 20, "Tag line should be present: $line")
    }

    @Test
    fun `rotates files when size limit exceeded`() {
        val dir = testDir("test3")
        val smallWriter = FileLogWriter(dir, fs, fileSizeLimit = 100L)
        repeat(50) { i ->
            smallWriter.log(Severity.Info, "x".repeat(10), "Tag", null)
        }
        smallWriter.beginShutdown()

        assertTrue(fs.exists(dir.resolve("log.0.txt")), "log.0 should exist")
        assertTrue(fs.exists(dir.resolve("log.1.txt")), "log.1 should exist after rotation")
    }

    @Test
    fun `beginShutdown waits for writes to complete`() {
        val dir = testDir("test4")
        val writer = newWriter(dir)
        writer.log(Severity.Info, "flush me", "Tag", null)
        writer.beginShutdown()

        val content = readAll(dir.resolve("log.0.txt"))
        assertContains(content, "flush me")
    }

    @Test
    fun `logFiles returns existing log files`() {
        val dir = testDir("test5")
        val writer = newWriter(dir)
        writer.log(Severity.Info, "a", "Tag", null)
        writer.beginShutdown()

        val files = writer.logFiles()
        assertFalse(files.isEmpty())
        assertTrue(
            files.all {
                val name = it.name
                name.startsWith("log.") && name.endsWith(".txt")
            },
        )
    }

    private fun readAll(path: Path): String {
        val buffer = Buffer()
        fs.source(path).use { source ->
            buffer.writeAll(source)
        }
        return buffer.readUtf8()
    }

    private fun readFirstLine(path: Path): String {
        val buffer = Buffer()
        fs.source(path).use { source ->
            buffer.writeAll(source)
        }
        return buffer.readUtf8().lines().first()
    }
}
