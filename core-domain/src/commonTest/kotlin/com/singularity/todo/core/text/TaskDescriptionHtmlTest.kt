package com.singularity.todo.core.text

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("fast")
class TaskDescriptionHtmlTest {
    @Test
    fun `legacy plain text is escaped and read literally`() {
        val html = TaskDescriptionHtml.canonical(html = null, plain = "<b>not bold</b> & 2 < 3")

        assertEquals("&lt;b&gt;not bold&lt;/b&gt; &amp; 2 &lt; 3", html)
        assertEquals("<b>not bold</b> & 2 < 3", TaskDescriptionHtml.plainText(html))
    }

    @Test
    fun `legacy line breaks survive the projection`() {
        val html = TaskDescriptionHtml.canonical(html = null, plain = "first\nsecond")

        assertEquals("first<br>second", html)
        assertEquals("first\nsecond", TaskDescriptionHtml.plainText(html))
    }

    @Test
    fun `matching html and plain keep the html`() {
        val html = "<p><strong>Milk</strong> &amp; eggs</p><p>Second line</p>"
        val plain = "Milk & eggs\nSecond line"

        assertEquals(html, TaskDescriptionHtml.storedHtml(html, plain))
        assertEquals(html, TaskDescriptionHtml.canonical(html, plain))
    }

    @Test
    fun `a plain write drops stale html`() {
        val stale = "<p><strong>Old</strong></p>"

        assertNull(TaskDescriptionHtml.storedHtml(stale, plain = "New"))
        assertEquals("New", TaskDescriptionHtml.canonical(stale, plain = "New"))
        assertEquals("New", TaskDescriptionHtml.plainText(TaskDescriptionHtml.canonical(stale, plain = "New")))
    }

    @Test
    fun `html without a plain projection derives one`() {
        val html = "<p>Hello <a href=\"note://n1\">world</a></p>"

        assertEquals("Hello world", TaskDescriptionHtml.storedPlain(html, plain = null))
        assertEquals("Hello world", TaskDescriptionHtml.plainText(html))
        assertEquals(html, TaskDescriptionHtml.canonical(html, plain = null))
    }

    @Test
    fun `html entities and numeric entities decode for search`() {
        assertEquals(
            "Tom & Jerry \u2014 3",
            TaskDescriptionHtml.htmlToPlainText("<p>Tom &amp; Jerry &mdash; 3</p>"),
        )
        assertEquals(
            "A B",
            TaskDescriptionHtml.htmlToPlainText("<p>A&#32;B</p>"),
        )
    }

    @Test
    fun `malformed html keeps the text that is not a tag`() {
        assertEquals("a < b", TaskDescriptionHtml.htmlToPlainText("a < b"))
        assertEquals("a b", TaskDescriptionHtml.htmlToPlainText("a </ b> b"))
    }

    @Test
    fun `empty content stays empty`() {
        assertEquals("", TaskDescriptionHtml.canonical(null, null))
        assertEquals("", TaskDescriptionHtml.plainText(null))
        assertEquals("", TaskDescriptionHtml.plainTextToHtml(""))
        assertNull(TaskDescriptionHtml.storedHtml("<p></p>", plain = ""))
    }

    @Test
    fun `block tags become line breaks and excess blank lines collapse`() {
        val html = "<h1>Title</h1><p>One</p><p>Two</p><br><p>Three</p>"

        assertEquals("Title\nOne\nTwo\nThree", TaskDescriptionHtml.htmlToPlainText(html))
    }

    @Test
    fun `script and style content is not searchable text`() {
        val html = "<p>Visible</p><script>hidden()</script><style>.x{}</style>"

        assertEquals("Visible", TaskDescriptionHtml.htmlToPlainText(html))
    }

    @Test
    fun `common named entities decode`() {
        assertEquals(
            "© € ™ £ ¥ ¢ « » • ′ −",
            TaskDescriptionHtml.htmlToPlainText(
                "<p>&copy; &euro; &trade; &pound; &yen; &cent; &laquo; &raquo; &bull; &prime; &minus;</p>",
            ),
        )
    }

    @Test
    fun `an emoji numeric entity decodes as a surrogate pair`() {
        assertEquals("\uD83D\uDE00", TaskDescriptionHtml.htmlToPlainText("&#x1F600;"))
    }

    @Test
    fun `a lone surrogate is left literal instead of producing invalid text`() {
        assertEquals("&#xD800;", TaskDescriptionHtml.htmlToPlainText("&#xD800;"))
        assertEquals("&#xDFFF;", TaskDescriptionHtml.htmlToPlainText("&#xDFFF;"))
    }

    @Test
    fun `nbsp collapses to a space`() {
        assertEquals("A B", TaskDescriptionHtml.htmlToPlainText("A&nbsp;B"))
    }

    @Test
    fun `nested blocks keep their line order`() {
        assertEquals(
            "one\ntwo\nthree",
            TaskDescriptionHtml.htmlToPlainText("<blockquote><p>one</p><p>two</p></blockquote><p>three</p>"),
        )
    }

    @Test
    fun `plain text to html round-trips through the projection`() {
        val text = "Tom & Jerry < 3\nsecond line"
        val html = TaskDescriptionHtml.plainTextToHtml(text)

        assertEquals(text, TaskDescriptionHtml.htmlToPlainText(html))
    }

    @Test
    fun `an empty html with legacy plain text is legacy`() {
        assertNull(TaskDescriptionHtml.storedHtml("", "Old"))
        assertEquals("Old", TaskDescriptionHtml.storedPlain("", "Old"))
        assertEquals("Old", TaskDescriptionHtml.canonical("", "Old"))
    }

    @Test
    fun `a malformed numeric entity is left literal`() {
        assertEquals("&#xZZ;", TaskDescriptionHtml.htmlToPlainText("&#xZZ;"))
    }
}
