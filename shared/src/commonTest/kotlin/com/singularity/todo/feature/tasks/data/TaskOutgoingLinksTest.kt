package com.singularity.todo.feature.tasks.data

import com.singularity.todo.feature.notes.LinkSchemes
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Re-enabled 2026-09-27.
 *
 * This class was previously `@Disabled` with the note that the three
 * `toLinksJson*` tests caused an OOM attributed to the Kover coverage runtime
 * (`docs/decisions/2026-09-25-test-jvm-heap-default.md`). That diagnosis was
 * wrong: the OOM was an unbounded loop in `toLinksJson` itself.
 *
 * Inside `buildString` the implicit receiver is the StringBuilder — which is a
 * `CharSequence` — so a bare `forEachIndexed` bound to
 * `CharSequence.forEachIndexed` rather than to the list, iterating over the
 * builder's own characters while appending to it. It only ever escaped notice
 * because an empty list short-circuits to `"[]"`, so any task with at least one
 * outgoing link would crash. Fixed by binding the list receiver explicitly.
 */
@Tag("fast")
class TaskOutgoingLinksTest {

    // ─── extractOutgoingLinks ─────────────────────────────────────────────────

    @Test
    fun `extractOutgoingLinks finds task URLs in plain text`() {
        val text = "See task://abc123 for details and task://def456 too"
        val links = extractOutgoingLinks(text)
        assertEquals(
            listOf("${LinkSchemes.TASK_PREFIX}abc123", "${LinkSchemes.TASK_PREFIX}def456"),
            links,
        )
    }

    @Test
    fun `extractOutgoingLinks finds note URLs in plain text`() {
        val text = "Related to note://note789 see also note://noteabc"
        val links = extractOutgoingLinks(text)
        assertEquals(
            listOf("${LinkSchemes.NOTE_PREFIX}note789", "${LinkSchemes.NOTE_PREFIX}noteabc"),
            links,
        )
    }

    @Test
    fun `extractOutgoingLinks deduplicates URLs`() {
        val text = "task://abc task://abc task://abc"
        val links = extractOutgoingLinks(text)
        assertEquals(listOf("${LinkSchemes.TASK_PREFIX}abc"), links)
    }

    @Test
    fun `extractOutgoingLinks returns empty for blank input`() {
        assertEquals(emptyList(), extractOutgoingLinks(""))
        assertEquals(emptyList(), extractOutgoingLinks("   "))
    }

    @Test
    fun `extractOutgoingLinks returns empty when no URLs present`() {
        val text = "This is just a plain task description without any links"
        assertEquals(emptyList(), extractOutgoingLinks(text))
    }

    @Test
    fun `extractOutgoingLinks handles mixed URLs`() {
        val text = "Check task://taskid and note://noteid for more"
        val links = extractOutgoingLinks(text)
        assertEquals(
            listOf(
                "${LinkSchemes.TASK_PREFIX}taskid",
                "${LinkSchemes.NOTE_PREFIX}noteid",
            ),
            links,
        )
    }

    @Test
    fun `extractOutgoingLinks handles IDs with underscores and hyphens`() {
        val text = "task://task_123-abc note://note_456-def"
        val links = extractOutgoingLinks(text)
        assertEquals(
            listOf(
                "${LinkSchemes.TASK_PREFIX}task_123-abc",
                "${LinkSchemes.NOTE_PREFIX}note_456-def",
            ),
            links,
        )
    }

    // ─── toLinksJson ─────────────────────────────────────────────────────────

    @Test
    fun `toLinksJson encodes empty list as empty array`() {
        assertEquals("[]", emptyList<String>().toLinksJson())
    }

    @Test
    fun `toLinksJson encodes single link`() {
        assertEquals("""["task://abc"]""", listOf("task://abc").toLinksJson())
    }

    @Test
    fun `toLinksJson encodes multiple links`() {
        val links = listOf("task://abc", "note://def")
        assertEquals("""["task://abc","note://def"]""", links.toLinksJson())
    }

    @Test
    fun `toLinksJson handles JSON-sensitive characters in IDs`() {
        // IDs are constrained to [a-zA-Z0-9_-]+ so no escaping needed
        val links = listOf("task://abc_123", "note://def-456")
        assertEquals("""["task://abc_123","note://def-456"]""", links.toLinksJson())
    }

    // ─── parseLinksJson ─────────────────────────────────────────────────────

    @Test
    fun `parseLinksJson decodes empty array`() {
        assertEquals(emptyList<String>(), "[]".parseLinksJson())
    }

    @Test
    fun `parseLinksJson decodes empty string`() {
        assertEquals(emptyList<String>(), "".parseLinksJson())
    }

    @Test
    fun `parseLinksJson decodes single link`() {
        assertEquals(listOf("task://abc"), """["task://abc"]""".parseLinksJson())
    }

    @Test
    fun `parseLinksJson decodes multiple links`() {
        val json = """["task://abc","note://def"]"""
        assertEquals(listOf("task://abc", "note://def"), json.parseLinksJson())
    }
}
