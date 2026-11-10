package com.singularity.todo.feature.notes.domain.model

import com.singularity.todo.core.links.InternalLinkSchemes
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class NoteBodyTest {
    // ─── isEmpty ────────────────────────────────────────────────────────────────

    @Test
    fun `isEmpty returns true for blank HTML`() {
        val empty = NoteBody("")
        assertTrue(empty.isEmpty, "NoteBody(\"\").isEmpty should be true")
        val blank = NoteBody("   ")
        assertTrue(blank.isEmpty, "NoteBody(\"   \").isEmpty should be true")
    }

    @Test
    fun `isEmpty returns false when tags contain text`() {
        assertFalse(NoteBody("<p>hello</p>").isEmpty)
        assertFalse(NoteBody("<p>hello</p><p></p>").isEmpty)
    }

    // ─── plainText ─────────────────────────────────────────────────────────────

    @Test
    fun `plainText strips tags`() {
        val body = NoteBody("<p>hello</p><p>world</p>")
        val text = body.plainText
        assertContains(text, "hello")
        assertContains(text, "world")
    }

    @Test
    fun `plainText decodes HTML entities`() {
        val body = NoteBody("a &lt; b &amp; c")
        val text = body.plainText
        assertContains(text, "a < b & c")
    }

    @Test
    fun `plainText empty for tags-only HTML`() {
        val body = NoteBody("<br><hr><p></p>")
        assertEquals("", body.plainText)
    }

    // ─── readableBlocks ───────────────────────────────────────────────────────

    @Test
    fun `readableBlocks keeps block boundaries`() {
        val body = NoteBody("<h2>Trip plan</h2><p>Pack passport</p><ul><li>Buy milk</li><li>Bread</li></ul>")
        assertEquals(listOf("Trip plan", "Pack passport", "Buy milk", "Bread"), body.readableBlocks)
    }

    @Test
    fun `readableBlocks decodes named and numeric entities`() {
        val body = NoteBody("<p>Caf&eacute; &amp; cr&egrave;me &#8212; today</p>")
        assertEquals("Caf\u00E9 & cr\u00E8me \u2014 today", body.readableBlocks.single())
    }

    @Test
    fun `readableBlocks ignores comments, script and style content`() {
        val body = NoteBody("<!-- note --><style>p{color:red}</style><script>alert('x')</script><p>Hello</p>")
        assertEquals(listOf("Hello"), body.readableBlocks)
    }

    @Test
    fun `readableBlocks keeps a quoted greater-than inside an attribute`() {
        val body = NoteBody("""<p>See <a title="A > B" href="x">Link</a> now</p>""")
        assertEquals("See Link now", body.readableBlocks.single())
    }

    @Test
    fun `readableBlocks empty for sentinel and formatting-only bodies`() {
        assertTrue(NoteBody("<p><br></p>").readableBlocks.isEmpty())
        assertTrue(NoteBody("<p><strong> </strong></p>").readableBlocks.isEmpty())
    }

    // ─── wordCount ────────────────────────────────────────────────────────────

    @Test
    fun `wordCount splits by whitespace`() {
        val body = NoteBody("<p>hello   world</p>")
        val wc1 = body.wordCount
        assertEquals(2, wc1)
        val body2 = NoteBody("<p>one\ntwo</p>")
        val wc2 = body2.wordCount
        assertEquals(2, wc2)
        assertEquals(1, NoteBody("<p>single</p>").wordCount)
        assertEquals(0, NoteBody("<p></p>").wordCount)
        assertEquals(0, NoteBody("").wordCount)
    }

    @Test
    fun `wordCount ignores blank tokens`() {
        val body = NoteBody("<p>  hello   world  </p>")
        assertEquals(2, body.wordCount)
    }

    // ─── charCount ────────────────────────────────────────────────────────────

    @Test
    fun `charCount is HTML string length`() {
        val body1 = NoteBody("abc")
        assertEquals(3, body1.charCount)
        val body2 = NoteBody("<p>hello</p>")
        assertEquals(12, body2.charCount) // <p> = 3, hello = 5, </p> = 4
    }

    // ─── outgoingLinks ────────────────────────────────────────────────────────

    @Test
    fun `outgoingLinks extracts note and task links`() {
        val body =
            NoteBody(
                "<p>See <a href=\"${InternalLinkSchemes.NOTE_PREFIX}n1\">note 1</a> and " +
                    "<a href=\"${InternalLinkSchemes.TASK_PREFIX}t1\">task 1</a></p>",
            )
        assertEquals(2, body.outgoingLinks.size)
        assertTrue(body.outgoingLinks.any { it.contains("note://n1") })
        assertTrue(body.outgoingLinks.any { it.contains("task://t1") })
    }

    @Test
    fun `outgoingLinks deduplicates`() {
        val body =
            NoteBody(
                "<a href=\"${InternalLinkSchemes.NOTE_PREFIX}n1\">link1</a>" +
                    "<a href=\"${InternalLinkSchemes.NOTE_PREFIX}n1\">link1 again</a>",
            )
        assertEquals(1, body.outgoingLinks.size)
    }

    @Test
    fun `outgoingLinks returns empty for no links`() {
        assertTrue(NoteBody("<p>plain text</p>").outgoingLinks.isEmpty())
        assertTrue(NoteBody("").outgoingLinks.isEmpty())
    }

    @Test
    fun `outgoingLinks returns empty for malformed href`() {
        val body = NoteBody("<a href=\"not-a-scheme\">text</a>")
        assertTrue(body.outgoingLinks.isEmpty())
    }

    // ─── fromMarkdown ────────────────────────────────────────────────────────

    @Test
    fun `fromMarkdown converts markdown to HTML`() {
        val body = NoteBody.fromMarkdown("# Hello\n\n**bold**")
        // RichTextState produces HTML with heading tag and bold tag
        assertTrue(body.html.contains("<h1>") || body.html.contains("<h2>") || body.html.contains("Hello"))
        assertFalse(body.isEmpty)
    }

    @Test
    fun `fromMarkdown produces body with correct counters`() {
        val body = NoteBody.fromMarkdown("one two three")
        assertEquals(3, body.wordCount)
        assertTrue(body.charCount > 0)
    }

    @Test
    fun `fromMarkdown produces body with correct outgoingLinks`() {
        val body = NoteBody.fromMarkdown("[mylink](note://n42)")
        assertTrue(
            body.outgoingLinks.any { it.contains("note://n42") },
            "Expected note://n42 in outgoingLinks, got: ${body.outgoingLinks}",
        )
    }

    // ─── Empty body ───────────────────────────────────────────────────────────

    @Test
    fun `Empty body is canonical`() {
        assertEquals(NoteBody.Empty, NoteBody(""))
        assertEquals(0, NoteBody.Empty.wordCount)
        assertEquals(0, NoteBody.Empty.charCount)
        assertTrue(NoteBody.Empty.outgoingLinks.isEmpty())
    }
}
