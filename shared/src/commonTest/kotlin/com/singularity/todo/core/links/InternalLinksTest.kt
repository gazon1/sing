package com.singularity.todo.core.links

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's link vocabulary: one rule for what a scheme is, one rule for what an
 * identifier may contain, one reader.
 *
 * The cases below are the ones the previous four grammars disagreed about. Each of
 * them used to have a different answer depending on which file asked.
 */
@Tag("fast")
class InternalLinksTest {

    // ─── parse ───────────────────────────────────────────────────────────────

    @Test
    fun `parse reads a note link`() {
        val target = InternalLinks.parse("note://abc123")
        assertEquals(InternalLinkSchemes.NOTE, target?.scheme)
        assertEquals("abc123", target?.id)
    }

    @Test
    fun `parse reads a task link`() {
        val target = InternalLinks.parse("task://xyz789")
        assertEquals(InternalLinkSchemes.TASK, target?.scheme)
        assertEquals("xyz789", target?.id)
    }

    @Test
    fun `parse accepts identifiers with hyphen and underscore`() {
        assertEquals("abc_123-def", InternalLinks.parse("task://abc_123-def")?.id)
    }

    @Test
    fun `parse rejects a scheme the app does not own`() {
        assertNull(InternalLinks.parse("https://example.com"))
        assertNull(InternalLinks.parse("notelink://abc"))
        assertNull(InternalLinks.parse("x-note://abc"))
    }

    @Test
    fun `parse rejects an empty identifier`() {
        assertNull(InternalLinks.parse("note://"))
        assertNull(InternalLinks.parse("task://"))
    }

    @Test
    fun `parse rejects an identifier outside the shared charset`() {
        assertNull(InternalLinks.parse("note://a b"))
        assertNull(InternalLinks.parse("note://a.b"))
        assertNull(InternalLinks.parse("note://a/b"))
    }

    @Test
    fun `parse rejects a string with no separator`() {
        assertNull(InternalLinks.parse(""))
        assertNull(InternalLinks.parse("note"))
        assertNull(InternalLinks.parse("://abc"))
    }

    // ─── urlFor ──────────────────────────────────────────────────────────────

    @Test
    fun `urlFor round-trips through parse`() {
        for (scheme in InternalLinkSchemes.ALL) {
            val url = InternalLinks.urlFor(scheme, "abc_123")
            val target = InternalLinks.parse(url)
            assertEquals(scheme, target?.scheme)
            assertEquals("abc_123", target?.id)
        }
    }

    @Test
    fun `a target knows its own url`() {
        val target = InternalLinkTarget(InternalLinkSchemes.NOTE, "abc")
        assertEquals("note://abc", target.url)
        assertEquals("note://", target.prefix)
    }

    // ─── findIn ──────────────────────────────────────────────────────────────

    @Test
    fun `findIn finds links in plain text`() {
        val matches = InternalLinks.findIn("See task://abc123 and note://def456")
        assertEquals(listOf("task://abc123", "note://def456"), matches.map { it.target.url })
    }

    @Test
    fun `findIn returns the range of each link`() {
        val text = "go task://abc now"
        val match = InternalLinks.findIn(text).single()
        assertEquals("task://abc", text.substring(match.range.first, match.range.last + 1))
    }

    @Test
    fun `findIn does not treat a foreign scheme's tail as ours`() {
        assertTrue(InternalLinks.findIn("xnote://abc").isEmpty())
        assertTrue(InternalLinks.findIn("x-note://abc").isEmpty())
        assertTrue(InternalLinks.findIn("notask://abc").isEmpty())
    }

    @Test
    fun `findIn ignores schemes the app does not own`() {
        assertTrue(InternalLinks.findIn("https://example.com/x").isEmpty())
        assertTrue(InternalLinks.findIn("file:///tmp/x").isEmpty())
    }

    @Test
    fun `findIn does not overlap`() {
        val matches = InternalLinks.findIn("task://abc note://def task://ghi")
        assertEquals(3, matches.size)
        assertEquals(
            listOf("task://abc", "note://def", "task://ghi"),
            matches.map { it.target.url },
        )
    }

    @Test
    fun `findIn keeps the order of the links`() {
        val matches = InternalLinks.findIn("a task://1 b note://2 c task://3")
        assertEquals(listOf("task", "note", "task"), matches.map { it.target.scheme })
    }

    @Test
    fun `findIn finds nothing in text without links`() {
        assertTrue(InternalLinks.findIn("just a description").isEmpty())
        assertTrue(InternalLinks.findIn("").isEmpty())
    }

    @Test
    fun `findIn finds a link that stands alone`() {
        val matches = InternalLinks.findIn("task://abc")
        assertEquals(1, matches.size)
        assertEquals("abc", matches.single().target.id)
    }

    @Test
    fun `isInternalScheme answers for the app's schemes only`() {
        assertTrue(InternalLinks.isInternalScheme(InternalLinkSchemes.NOTE))
        assertTrue(InternalLinks.isInternalScheme(InternalLinkSchemes.TASK))
        assertTrue(!InternalLinks.isInternalScheme("https"))
        assertTrue(!InternalLinks.isInternalScheme(""))
    }
}
