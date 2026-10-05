package com.singularity.todo.feature.ai.tools

import kotlin.time.Instant
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.core.platform.HostEnvironmentPort
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AdrStorage] writing and reading a real ADR, which means real directories.
 *
 * Tagged `slow` for the reason the tag exists: it creates and deletes directories on
 * disk, so it crosses the process boundary and its cleanup can be left behind if it
 * fails. The half of the behaviour that needs no filesystem is
 * [AdrStorageResolutionTest], which is `fast`.
 */
@Tag("slow")
class AdrStorageRoundTripTest {

    private val temporaries = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaries.forEach { it.deleteRecursively() }
        temporaries.clear()
    }

    private class FakeHost(private val workingDir: String, private val homeDir: String) : HostEnvironmentPort {
        override fun workingDirectory(): String = workingDir
        override fun homeDirectory(): String = homeDir
    }

    private fun tempDir(): File = createTempDirectory("adr-test").toFile().also { temporaries += it }

    /** A storage whose working directory [hasDecisionsDir] determines, and whose home is empty. */
    private fun storageFor(hasDecisionsDir: Boolean): AdrStorage {
        val working = tempDir()
        if (hasDecisionsDir) working.resolve("docs/decisions").mkdirs()
        return AdrStorage(
            host = FakeHost(working.absolutePath, tempDir().absolutePath),
            // A fixed instant, so a round-trip test can assert the frontmatter
            // date instead of accepting the day it ran (#91).
            clock = FakeClock(Instant.parse("2026-09-16T10:00:00Z")),
            timeZone = TEST_TZ,
        )
    }

    @Test
    fun `a repository checkout is preferred over the home fallback`() {
        val storage = storageFor(hasDecisionsDir = true)

        assertTrue(
            storage.decisionsDir().endsWith("docs/decisions"),
            "expected the project directory, got ${storage.decisionsDir()}",
        )
    }

    @Test
    fun `without a checkout it falls back to a per-user directory`() {
        val storage = storageFor(hasDecisionsDir = false)

        assertTrue(
            storage.decisionsDir().contains(".singularity-todo"),
            "expected the home fallback, got ${storage.decisionsDir()}",
        )
    }

    @Test
    fun `an ADR survives a write and a read`() {
        val storage = storageFor(hasDecisionsDir = true)

        val path = storage.writeAdr(
            slug = "2026-10-05-example",
            title = "Example",
            tags = listOf("arch"),
            body = "Because.\n",
        )

        assertTrue(Path.of(path).exists())
        val read = storage.readAdr("2026-10-05-example")!!
        assertEquals("Example", read.title)
        assertEquals(listOf("arch"), read.tags)
        assertTrue(read.body.contains("Because."), read.body)
    }

    @Test
    fun `the written frontmatter carries an ISO date`() {
        val storage = storageFor(hasDecisionsDir = true)

        storage.writeAdr("2026-10-05-dated", "Dated", emptyList(), "Body.")

        val content = Path.of(storage.filePath("2026-10-05-dated")).readText()
        val stored = Regex("""date: (\S+)""").find(content)?.groupValues?.get(1)
        assertTrue(
            stored != null && Regex("""\d{4}-\d{2}-\d{2}""").matches(stored),
            "expected an ISO date in the frontmatter, got ${stored ?: content}",
        )
    }

    @Test
    fun `listing an empty directory yields nothing rather than failing`() {
        val storage = storageFor(hasDecisionsDir = true)

        assertEquals(emptyList(), storage.listAdrs())
    }

    @Test
    fun `an ADR written by hand without frontmatter is still readable`() {
        val storage = storageFor(hasDecisionsDir = true)
        val slug = "2026-10-05-bare"
        Path.of(storage.filePath(slug)).also { it.parent.createDirectories() }
            .writeText("Just a body, no frontmatter.\n")

        val read = storage.readAdr(slug)!!
        assertEquals("(no title)", read.title)
        assertEquals("", read.date)
    }
}
