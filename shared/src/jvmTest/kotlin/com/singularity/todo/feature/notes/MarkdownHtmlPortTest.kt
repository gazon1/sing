package com.singularity.todo.feature.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownHtmlPortTest {

    private val port: MarkdownHtmlPort = RichEditorMarkdownHtmlPort()

    @Test
    fun `toHtml converts heading`() {
        val html = port.toHtml("# Hello")
        assertTrue(html.contains("<h1>Hello</h1>"), "Expected h1 tag, got: $html")
    }

    @Test
    fun `toHtml converts h2`() {
        val html = port.toHtml("## World")
        assertTrue(html.contains("<h2>World</h2>"), "Expected h2 tag, got: $html")
    }

    @Test
    fun `toHtml converts h3`() {
        val html = port.toHtml("### Sub")
        assertTrue(html.contains("<h3>Sub</h3>"), "Expected h3 tag, got: $html")
    }

    @Test
    fun `toHtml converts bold`() {
        val html = port.toHtml("**bold**")
        assertTrue(html.contains("<strong>bold</strong>"), "Expected strong tag, got: $html")
    }

    @Test
    fun `toHtml converts italic`() {
        val html = port.toHtml("*italic*")
        assertTrue(html.contains("<em>italic</em>"), "Expected em tag, got: $html")
    }

    @Test
    fun `toHtml converts bullet list`() {
        val html = port.toHtml("- item1\n- item2")
        assertTrue(html.contains("<li>item1</li>"), "Expected bullet list item 1, got: $html")
        assertTrue(html.contains("<li>item2</li>"), "Expected bullet list item 2, got: $html")
    }

    @Test
    fun `toHtml converts code block`() {
        val html = port.toHtml("```\nprintln('hi')\n```")
        assertTrue(html.contains("<pre><code>"), "Expected code block, got: $html")
    }

    @Test
    fun `toHtml converts inline code`() {
        val html = port.toHtml("`println`")
        assertTrue(html.contains("<code>println</code>"), "Expected inline code, got: $html")
    }

    @Test
    fun `toHtml converts link`() {
        val html = port.toHtml("[Example](https://example.com)")
        assertTrue(html.contains("<a href=\"https://example.com\">"), "Expected link, got: $html")
        assertTrue(html.contains("Example"), "Expected link text, got: $html")
    }

    @Test
    fun `toMarkdown converts h1`() {
        assertEquals("# Hello", port.toMarkdown("<h1>Hello</h1>").trim())
    }

    @Test
    fun `toMarkdown converts bold`() {
        assertEquals("**bold**", port.toMarkdown("<strong>bold</strong>").trim())
    }

    @Test
    fun `toMarkdown converts italic`() {
        assertEquals("*italic*", port.toMarkdown("<em>italic</em>").trim())
    }

    @Test
    fun `toMarkdown converts link`() {
        val result = port.toMarkdown("<a href=\"https://example.com\">Example</a>")
        assertTrue(result.contains("[Example](https://example.com)"), "Expected markdown link, got: $result")
    }

    @Test
    fun `round-trip preserves heading and formatting`() {
        val original = "# My Note\n\nThis is **bold** and *italic*."
        val html = port.toHtml(original)
        val recovered = port.toMarkdown(html)
        assertTrue(recovered.contains("# My Note"), "Round-trip lost heading: $recovered")
        assertTrue(recovered.contains("**bold**"), "Round-trip lost bold: $recovered")
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", port.toHtml(""))
        assertEquals("", port.toMarkdown(""))
    }
}
