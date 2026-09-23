package com.singularity.todo.feature.notes.domain

import com.singularity.todo.feature.notes.LinkRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteContentMapperTest {

    @Test
    fun `toMarkdown converts HTML paragraph to plain text`() {
        val html = "<p>Hello world</p>"
        val markdown = NoteContentMapper.toMarkdown(html)
        assertEquals("Hello world", markdown)
    }

    @Test
    fun `toHtml converts markdown bold to HTML`() {
        // Library outputs <b> not <strong>
        val markdown = "Hello **world**"
        val html = NoteContentMapper.toHtml(markdown)
        assertTrue(html.contains("<b>world</b>"), "Expected <b> tag in: $html")
    }

    @Test
    fun `outgoingLinkUrls extracts note links`() {
        val html = """<p>See <a href="note://abc123">this note</a>.</p>"""
        val links = NoteContentMapper.outgoingLinkUrls(html)
        assertEquals(1, links.size)
        assertEquals("note://abc123", links[0])
    }

    @Test
    fun `outgoingLinkUrls extracts task links`() {
        val html = """<p>See <a href="task://xyz789">this task</a>.</p>"""
        val links = NoteContentMapper.outgoingLinkUrls(html)
        assertEquals(1, links.size)
        assertEquals("task://xyz789", links[0])
    }

    @Test
    fun `outgoingLinkUrls returns empty for no links`() {
        val html = "<p>No links here</p>"
        assertEquals(emptyList(), NoteContentMapper.outgoingLinkUrls(html))
    }

    @Test
    fun `roundtrip preserves content`() {
        val original = "<p>Hello <b>world</b></p>"
        val markdown = NoteContentMapper.toMarkdown(original)
        val reconstructed = NoteContentMapper.toHtml(markdown)
        assertTrue(reconstructed.contains("Hello"))
        assertTrue(reconstructed.contains("world"))
    }

    @Test
    fun `outgoingLinkUrls extracts mixed note and task links`() {
        val html = """<p><a href="note://n1">Note 1</a> and <a href="task://t1">Task 1</a> and <a href="note://n2">Note 2</a>.</p>"""
        val links = NoteContentMapper.outgoingLinkUrls(html)
        assertEquals(3, links.size)
        assertEquals("note://n1", links[0])
        assertEquals("task://t1", links[1])
        assertEquals("note://n2", links[2])
    }
}
