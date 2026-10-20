package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The search over the corpus, against a real directory tree.
 *
 * Tagged `slow` for the reason the tag exists: it creates and deletes directories on
 * disk.
 *
 * The properties that matter are not "does it find things" — it obviously finds things —
 * but the three the corpus rules decide:
 *
 * * the **archive** is not searchable, because an archived decision is not one;
 * * the **deferred backlog** is not a decision, and is marked as what it is;
 * * an **unavailable corpus** is not the same answer as "nothing matched", because an
 *   agent that cannot tell them apart reports "no decision mentions this" when the
 *   truth is that there was nothing to search.
 */
@Tag("slow")
class AdrSearchTest {

    private val temporaries = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaries.forEach { it.deleteRecursively() }
        temporaries.clear()
    }

    private class FakeHost(private val workingDir: String, private val homeDir: String) : HostEnvironmentPort {
        override fun workingDirectory(): String = workingDir
        override fun homeDirectory(): String = homeDir
        override fun applicationDataDirectory(): String = homeDir + "/.singularity-todo"
    }

    /** A checkout with a decisions directory, a backlog, an archive and one document. */
    private fun checkout(): File = createTempDirectory("adr-search").toFile().also { temporaries += it }
        .apply {
            resolve("docs/decisions/deferred").mkdirs()
            resolve("docs/decisions/archive").mkdirs()
            resolve("docs/architecture").mkdirs()
            writeDecision(
                "2026-10-01-sync-conflict-policy",
                title = "The sync conflict policy",
                date = "2026-10-01",
                status = "accepted",
                tags = listOf("sync", "conflict"),
                body = "Conflicts are resolved by last write wins, and the loser is kept.",
            )
            writeDecision(
                "2026-09-01-older-note",
                title = "An older decision",
                date = "2026-09-01",
                status = "open",
                tags = listOf("misc"),
                body = "Nothing about sync here.",
            )
            writeDecision(
                "2026-08-01-archived-note",
                title = "An archived decision about sync",
                date = "2026-08-01",
                status = "archived",
                tags = listOf("sync"),
                body = "Archived, and therefore not a decision any more.",
                directory = "docs/decisions/archive",
            )
            resolve("docs/decisions/deferred/2026-07-01-planned-sync.md").writeText(
                """
                ---
                title: A planned sync change
                date: 2026-07-01
                status: open
                tags: [sync, planned]
                ---

                The backlog entry about sync that has not been decided yet.
                """.trimIndent(),
            )
            resolve("docs/architecture/sync.md").writeText(
                """
                # Sync

                The sync engine keeps two databases in step. A conflict is resolved by
                last write wins.
                """.trimIndent(),
            )
        }

    private fun File.writeDecision(
        slug: String,
        title: String,
        date: String,
        status: String,
        tags: List<String>,
        body: String,
        directory: String = "docs/decisions",
    ) {
        resolve("$directory/$slug.md").writeText(
            """
            ---
            title: $title
            date: $date
            status: $status
            tags: [${tags.joinToString(", ")}]
            ---

            $body
            """.trimIndent(),
        )
    }

    private fun storage(root: File): AdrStorage {
        val home = createTempDirectory("adr-search-home").toFile().also { temporaries += it }
        return AdrStorage(
            host = FakeHost(root.absolutePath, home.absolutePath),
            clock = FakeClock(Instant.parse("2026-10-16T10:00:00Z")),
            timeZone = TEST_TZ,
        )
    }

    @Test
    fun `a decision matching by title is found`() {
        val envelope = storage(checkout()).search.searchDecisions("sync conflict")

        assertTrue(envelope.available)
        assertEquals(null, envelope.error)
        val hit = envelope.hits.firstOrNull { it.slug == "2026-10-01-sync-conflict-policy" }
        assertNotNull(hit, "expected the sync decision, got ${envelope.hits.map { it.slug }}")
        assertEquals("accepted", hit.status)
        assertEquals("decision", hit.kind)
        assertTrue(hit.tags.contains("sync"))
    }

    @Test
    fun `a decision matching by body is found with a snippet`() {
        val envelope = storage(checkout()).search.searchDecisions("last write wins")

        assertTrue(envelope.available)
        val hit = envelope.hits.firstOrNull { it.slug == "2026-10-01-sync-conflict-policy" }
        assertNotNull(hit, "expected the sync decision, got ${envelope.hits.map { it.slug }}")
        assertTrue(
            hit.snippet.contains("last write wins"),
            "the snippet should show the matching line, got ${hit.snippet}",
        )
    }

    @Test
    fun `an archived decision is not a decision`() {
        val envelope = storage(checkout()).search.searchDecisions("archived decision about sync")

        assertTrue(envelope.available)
        assertTrue(
            envelope.hits.none { it.slug == "2026-08-01-archived-note" },
            "an archived file must not be searchable, got ${envelope.hits.map { it.slug }}",
        )
    }

    @Test
    fun `the deferred backlog is found and marked as a backlog entry`() {
        val envelope = storage(checkout()).search.searchDecisions("planned sync change")

        assertTrue(envelope.available)
        val hit = envelope.hits.firstOrNull { it.slug == "2026-07-01-planned-sync" }
        assertNotNull(hit, "expected the backlog entry, got ${envelope.hits.map { it.slug }}")
        assertEquals("deferred", hit.kind)
    }

    @Test
    fun `a blank query is an error, not an empty result`() {
        val envelope = storage(checkout()).search.searchDecisions("   ")

        assertNotNull(envelope.error, "a blank query must say so")
        assertFalse(envelope.available)
        assertTrue(envelope.hits.isEmpty())
    }

    @Test
    fun `a corpus that is not there is not the same answer as no match`() {
        val empty = createTempDirectory("adr-search-empty").toFile().also { temporaries += it }
        val envelope = storage(empty).search.searchDecisions("anything")

        assertFalse(envelope.available, "there was no corpus to search")
        assertTrue(envelope.hits.isEmpty())
        assertTrue(envelope.warnings.isNotEmpty(), "the report should say why")
    }

    @Test
    fun `a search that matches nothing says so without an error`() {
        val envelope = storage(checkout()).search.searchDecisions("nothing matches this phrase")

        assertTrue(envelope.available, "the corpus was searched")
        assertEquals(null, envelope.error)
        assertTrue(envelope.hits.isEmpty())
    }

    @Test
    fun `the limit is clamped rather than trusted`() {
        val envelope = storage(checkout()).search.searchDecisions("sync", limit = 10_000)

        assertTrue(
            envelope.hits.size <= SearchLimits.MAX,
            "the limit must be clamped, got ${envelope.hits.size} hits",
        )
    }

    @Test
    fun `newer decisions come first`() {
        val envelope = storage(checkout()).search.searchDecisions("sync")

        val dated = envelope.hits.filter { it.kind == "decision" }
        assertTrue(dated.size >= 2, "expected several decisions, got ${dated.map { it.slug }}")
        assertEquals(
            dated.map { it.date }.sortedDescending(),
            dated.map { it.date },
            "hits must be ordered newest first",
        )
    }

    @Test
    fun `the document scope finds the architecture documents`() {
        val envelope = storage(checkout()).search.searchDocuments("last write wins", scope = SearchScope.ARCHITECTURE)

        assertTrue(envelope.available)
        val hit = envelope.hits.firstOrNull { it.path.endsWith("docs/architecture/sync.md") }
        assertNotNull(hit, "expected the sync document, got ${envelope.hits.map { it.path }}")
        assertEquals("architecture", hit.scope)
        assertEquals("document", hit.kind)
    }

    @Test
    fun `the decisions scope answers the structured question`() {
        val envelope = storage(checkout()).search.searchDocuments("sync", scope = SearchScope.DECISIONS)

        assertTrue(envelope.available)
        assertTrue(envelope.hits.isNotEmpty())
        assertTrue(envelope.hits.all { it.scope == "decisions" })
    }
}
