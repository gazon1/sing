package com.singularity.todo.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The legacy body as words.
 *
 * A body arrives as Quill Delta JSON, as HTML, or as plain text. Each test
 * names one shape the app would otherwise show as a raw document.
 */
class LegacyTextTest {
    @Test
    fun `delta text and inline formatting become html`() {
        val delta =
            """[{"insert":"plain "},{"insert":"bold","attributes":{"bold":true}},""" +
                """{"insert":" and "},{"insert":"link","attributes":{"link":"https://example.com"}},""" +
                """{"insert":"\n"}]"""

        val html = LegacyText.toHtml(delta)

        assertEquals(
            "<p>plain <strong>bold</strong> and <a href=\"https://example.com\">link</a></p>",
            html,
        )
    }

    @Test
    fun `a bullet list becomes a list and keeps its item text`() {
        val delta =
            """[{"insert":"one"},{"insert":"\n","attributes":{"list":"bullet"}},""" +
                """{"insert":"two"},{"insert":"\n","attributes":{"list":"bullet"}}]"""

        val html = LegacyText.toHtml(delta)

        assertEquals("<ul><li>one</li><li>two</li></ul>", html)
    }

    @Test
    fun `an indented item nests inside the previous one`() {
        val delta =
            """[{"insert":"parent"},{"insert":"\n","attributes":{"list":"bullet"}},""" +
                """{"insert":"child"},{"insert":"\n","attributes":{"indent":1,"list":"bullet"}}]"""

        assertEquals(
            "<ul><li>parent<ul><li>child</li></ul></li></ul>",
            LegacyText.toHtml(delta),
        )
    }

    @Test
    fun `a header becomes a heading and h4 does not outrun the editor`() {
        val delta = """[{"insert":"title"},{"insert":"\n","attributes":{"header":4}}]"""

        assertEquals("<h3>title</h3>", LegacyText.toHtml(delta))
    }

    @Test
    fun `a divider becomes a rule without a blank paragraph after it`() {
        val delta = """[{"insert":{"divider":true}},{"insert":"\n"}]"""

        assertEquals("<hr>", LegacyText.toHtml(delta))
    }

    @Test
    fun `plain text is escaped and split into paragraphs`() {
        assertEquals(
            "<p>a &lt; b</p><p>second</p>",
            LegacyText.toHtml("a < b\nsecond"),
        )
    }

    @Test
    fun `html passes through as html`() {
        val html = "<p>already <strong>html</strong></p>"

        assertEquals(html, LegacyText.toHtml(html))
    }

    @Test
    fun `plain text drops the tags`() {
        assertEquals("already html", LegacyText.toPlainText("<p>already <strong>html</strong></p>"))
    }

    @Test
    fun `plain text of a delta is words, not json`() {
        val delta =
            """[{"insert":"line one\n"},{"insert":"item"},{"insert":"\n","attributes":{"list":"bullet"}}]"""

        val text = LegacyText.toPlainText(delta)

        assertFalse(text.contains("\"insert\""), "the document must not leak into the text: $text")
        assertTrue(text.contains("line one"), text)
        assertTrue(text.contains("• item"), text)
    }

    @Test
    fun `an array that is not a delta is read as text`() {
        val raw = """["not", "a", "delta"]"""

        assertEquals("<p>${"[\"not\", \"a\", \"delta\"]"}</p>", LegacyText.toHtml(raw))
    }

    @Test
    fun `a script link is not made clickable`() {
        val delta = """[{"insert":"click"},{"insert":"\n","attributes":{"link":"javascript:alert(1)"}}]"""

        assertFalse(LegacyText.toHtml(delta).contains("<a"), "a legacy link may not carry a script")
    }

    @Test
    fun `entities in plain text are unescaped`() {
        assertEquals("ytb -> notebooklm", LegacyText.toPlainText("ytb -&gt; notebooklm"))
        assertEquals("<p>a &gt; b</p>", LegacyText.toHtml("a &gt; b"))
    }

    @Test
    fun `a title that is only an anchor becomes its text`() {
        val raw = """<a href="https://example.com/x">https://example.com/x</a>"""

        assertEquals("https://example.com/x", LegacyText.toPlainText(raw))
    }
}
