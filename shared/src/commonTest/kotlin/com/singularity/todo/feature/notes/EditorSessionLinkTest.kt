package com.singularity.todo.feature.notes

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The link insertion of the note editor.
 *
 * The library refuses a collapsed selection — `addLinkToSelection` returns without
 * touching the text — so a caret used to swallow the link: the dialog closed, the
 * note stayed as it was, and nothing on the screen said so.
 *
 * These tests pin the two cases. A caret gets the link, written at the caret; a
 * selection keeps the text it had and becomes the link.
 */
@Tag("fast")
class EditorSessionLinkTest {
    @Test
    fun `a caret gets the link, written at the caret`() {
        val state = RichTextState().apply { setHtml("<p>Hello world</p>") }
        state.selection = TextRange(5) // the caret is inside the word
        val session = session(state)

        session.insertLink(url = "https://example.com", text = "https://example.com")

        val html = state.toHtml()
        assertContains(html, """href="https://example.com"""")
        assertContains(html, ">https://example.com</a>")
        // The text went in where the caret was, not at the end of the note.
        assertContains(state.toText(), "Hellohttps://example.com world")
    }

    @Test
    fun `a caret in an empty note gets the link`() {
        val state = RichTextState().apply { setHtml("") }
        state.selection = TextRange(0)
        val session = session(state)

        session.insertLink(url = "https://example.com", text = "https://example.com")

        assertContains(state.toHtml(), """href="https://example.com"""")
    }

    @Test
    fun `a selection becomes the link and keeps its text`() {
        val state = RichTextState().apply { setHtml("<p>Hello world</p>") }
        state.selection = TextRange(0, 5) // "Hello" is selected
        val session = session(state)

        session.insertLink(url = "https://example.com", text = "https://example.com")

        val html = state.toHtml()
        assertContains(html, """href="https://example.com"""")
        assertContains(html, ">Hello</a>")
        // The address was not written into the note: the selected text is the link.
        assertFalse(html.contains("https://example.com</a>"))
        assertEquals("Hello world", state.toText())
    }

    @Test
    fun `the link reaches the draft`() {
        var dispatched: String? = null
        val state = RichTextState().apply { setHtml("<p>Hello world</p>") }
        state.selection = TextRange(5)
        val session =
            EditorSession(
                richTextState = state,
                lastDispatchedHtml = state.toHtml(),
                onBodyChange = { _, html -> dispatched = html },
                id = "n1",
            )

        session.insertLink(url = "https://example.com", text = "https://example.com")

        assertContains(dispatched.orEmpty(), """href="https://example.com"""")
    }

    /** A session over [state] that dispatches to nowhere — the draft is not under test here. */
    private fun session(state: RichTextState): EditorSession =
        EditorSession(
            richTextState = state,
            lastDispatchedHtml = state.toHtml(),
            onBodyChange = { _, _ -> },
            id = "n1",
        )
}
