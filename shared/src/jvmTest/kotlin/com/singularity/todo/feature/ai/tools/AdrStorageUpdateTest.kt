package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [AdrStorage.updateAdr] — the tool that changes a decision's status without
 * touching what the decision says.
 *
 * Tagged `slow` for the reason the tag exists: real files on a real filesystem.
 * Most assertions here are about what is *not* written as much as what is — the
 * point of the tool is that it edits a frontmatter block and leaves the reasoning
 * alone, and a test that only checked the new status would not notice if it had
 * eaten the body.
 */
@Tag("slow")
class AdrStorageUpdateTest {

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

    private fun tempDir(): File = createTempDirectory("adr-update").toFile().also { temporaries += it }

    /** A storage over a fresh checkout with `docs/decisions/` already present. */
    private fun storage(): AdrStorage {
        val working = tempDir()
        working.resolve("docs/decisions").mkdirs()
        return AdrStorage(
            host = FakeHost(working.absolutePath, tempDir().absolutePath),
            clock = FakeClock(Instant.parse("2026-10-10T10:00:00Z")),
            timeZone = TEST_TZ,
        )
    }

    private fun AdrStorage.seed(slug: String, body: String = BODY): Path {
        writeAdr(slug, "A title for $slug", listOf("arch"), body)
        return Path.of(filePath(slug))
    }

    private fun AdrStorage.field(slug: String, name: String): String? =
        Regex("""(?m)^${Regex.escape(name)}: (.*)$""")
            .find(Path.of(filePath(slug)).readText())
            ?.groupValues?.get(1)
            ?.trim()

    /**
     * Exactly the lines after the *closing* fence, so a rewrite cannot hide behind a
     * `trim()` — and so a reordering of the frontmatter is not mistaken for a change
     * to the decision: `updateAdr` deliberately moves `status` to the top, and this
     * helper must not report that as damage.
     */
    private fun bodyOf(path: Path): String {
        val lines = path.readText().lines()
        val start = lines.indexOfFirst { it.trim() == "---" }
        val closing = lines.drop(start + 1).indexOfFirst { it.trim() == "---" }
        return lines.drop(start + closing + 2).joinToString("\n")
    }

    @Test
    fun `status becomes the one that was asked for`() {
        val storage = storage()
        storage.seed(SLUG)

        storage.updateAdr(SLUG, status = "accepted")

        assertEquals("accepted", storage.field(SLUG, "status"))
    }

    /**
     * The load-bearing test. If the tool ever re-serialises the document instead of
     * editing its frontmatter, this is what catches it.
     */
    @Test
    fun `the body survives byte for byte`() {
        val storage = storage()
        val path = storage.seed(SLUG)
        val before = bodyOf(path)

        storage.updateAdr(SLUG, status = "accepted")

        assertEquals(before, bodyOf(path), "updateAdr must not touch anything after the frontmatter")
    }

    @Test
    fun `the rest of the frontmatter survives`() {
        val storage = storage()
        storage.seed(SLUG)

        storage.updateAdr(SLUG, status = "accepted")

        val parsed = storage.readAdr(SLUG)!!
        assertEquals("A title for $SLUG", parsed.title)
        assertEquals(listOf("arch"), parsed.tags)
    }

    @Test
    fun `a second update replaces the first rather than stacking fields`() {
        val storage = storage()
        storage.seed(SLUG)

        storage.updateAdr(SLUG, status = "accepted")
        storage.updateAdr(SLUG, status = "open")

        val raw = Path.of(storage.filePath(SLUG)).readText()
        assertEquals(1, Regex("""(?m)^status:""").findAll(raw).count(), raw)
        assertEquals("open", storage.field(SLUG, "status"))
    }

    @Test
    fun `superseded records what replaced it`() {
        val storage = storage()
        storage.seed(SLUG)
        storage.seed(OTHER)

        storage.updateAdr(SLUG, status = "superseded", supersededBy = OTHER)

        assertEquals("superseded", storage.field(SLUG, "status"))
        assertEquals(OTHER, storage.field(SLUG, "superseded-by"))
    }

    @Test
    fun `superseded without a target is refused`() {
        val storage = storage()
        val path = storage.seed(SLUG)
        val before = path.readText()

        val failure = assertFailsWith<IllegalArgumentException> {
            storage.updateAdr(SLUG, status = "superseded")
        }

        assertTrue(failure.message!!.contains("requires supersededBy"), failure.message!!)
        assertEquals(before, path.readText(), "a refused update must not write")
    }

    @Test
    fun `a target that does not exist is refused`() {
        val storage = storage()
        storage.seed(SLUG)

        val failure = assertFailsWith<IllegalArgumentException> {
            storage.updateAdr(SLUG, status = "superseded", supersededBy = "2026-10-10-nothing-here")
        }

        assertTrue(failure.message!!.contains("does not exist"), failure.message!!)
    }

    /** A decision can be replaced by something not yet written up — the backlog. */
    @Test
    fun `a target in the deferred backlog is accepted`() {
        val storage = storage()
        storage.seed(SLUG)
        val backlog = Path.of(storage.decisionsDir(), "deferred")
        backlog.createDirectories()
        Path.of(backlog.toString(), "$BACKLOG.md").writeText(
            "---\ntitle: \"Planned\"\ndate: 2000-01-01\n---\n\nLater.\n",
        )

        storage.updateAdr(SLUG, status = "superseded", supersededBy = BACKLOG)

        assertEquals(BACKLOG, storage.field(SLUG, "superseded-by"))
    }

    @Test
    fun `a target may be written as a markdown link`() {
        val storage = storage()
        storage.seed(SLUG)
        storage.seed(OTHER)

        storage.updateAdr(SLUG, status = "superseded", supersededBy = "[$OTHER]($OTHER.md)")

        assertEquals(OTHER, storage.field(SLUG, "superseded-by"))
    }

    @Test
    fun `archived cannot be set because it describes a location`() {
        val storage = storage()
        storage.seed(SLUG)

        val failure = assertFailsWith<IllegalArgumentException> {
            storage.updateAdr(SLUG, status = "archived")
        }

        assertTrue(failure.message!!.contains("archive"), failure.message!!)
    }

    @Test
    fun `a status outside the vocabulary is refused`() {
        val storage = storage()
        storage.seed(SLUG)

        val failure = assertFailsWith<IllegalArgumentException> {
            storage.updateAdr(SLUG, status = "CLOSED")
        }

        assertTrue(failure.message!!.contains("Invalid status"), failure.message!!)
    }

    @Test
    fun `creating an ADR is not this tool's job`() {
        val storage = storage()

        val failure = assertFailsWith<IllegalStateException> {
            storage.updateAdr(SLUG, status = "accepted")
        }

        assertTrue(failure.message!!.contains("writeAdr"), failure.message!!)
        assertTrue(!Path.of(storage.filePath(SLUG)).exists(), "a refused update must not create the file")
    }

    @Test
    fun `a document with no frontmatter is left alone`() {
        val storage = storage()
        val path = Path.of(storage.filePath(SLUG))
        path.parent.createDirectories()
        val original = "Just a body, no frontmatter.\n"
        path.writeText(original)

        assertFailsWith<IllegalStateException> { storage.updateAdr(SLUG, status = "accepted") }

        assertEquals(original, path.readText())
    }

    /**
     * An unterminated fence means the document has no frontmatter block at all, so
     * it is refused before the block-level check is ever reached — the message says
     * exactly that, which is more useful than "malformed" would have been.
     */
    @Test
    fun `an unterminated frontmatter block is left alone`() {
        val storage = storage()
        val path = Path.of(storage.filePath(SLUG))
        path.parent.createDirectories()
        val original = "---\ntitle: \"Half a fence\"\ndate: 2026-10-10\n\nBody with no closing fence.\n"
        path.writeText(original)

        val failure = assertFailsWith<IllegalStateException> { storage.updateAdr(SLUG, status = "accepted") }

        assertTrue(failure.message!!.contains("no parseable frontmatter"), failure.message!!)
        assertEquals(original, path.readText())
    }

    /** Frontmatter not at the top of the file is a different failure from a missing one. */
    @Test
    fun `frontmatter that does not start the file is refused`() {
        val storage = storage()
        val path = Path.of(storage.filePath(SLUG))
        path.parent.createDirectories()
        val original = "Preamble.\n---\ntitle: \"Late\"\ndate: 2026-10-10\n---\n\nBody.\n"
        path.writeText(original)

        assertFailsWith<IllegalArgumentException> { storage.updateAdr(SLUG, status = "accepted") }

        assertEquals(original, path.readText())
    }

    @Test
    fun `a path-traversing slug never reaches the filesystem`() {
        val storage = storage()

        assertFailsWith<IllegalArgumentException> { storage.updateAdr("../../etc/passwd", status = "accepted") }
    }

    private companion object {
        const val SLUG = "2026-10-10-example"
        const val OTHER = "2026-10-10-replacement"
        const val BACKLOG = "2026-10-10-planned-replacement"

        /**
         * Deliberately awkward: a horizontal rule in the body, a fenced block that
         * itself contains `---` lines, and no trailing newline. A naive "split on the
         * first two `---`" rewrite mangles at least one of these.
         */
        const val BODY: String = """
            # A title for the decision

            ## Context

            We had to choose between three things.

            ## Decision

            Choose the first one.

            ## Consequences

            ---

            ```text
            ---
            not: a fence
            ---
            ```

            Some trailing prose with no newline at the end"""
    }
}
