package com.singularity.todo.core.ui.rich

import androidx.compose.runtime.Stable
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Holds the mutable state of one rich-editing session.
 *
 * Feature-independent on purpose: the notes editor and the task-description
 * editor both hoist a [RichTextState] out of the composable hierarchy so a
 * toolbar can share it. What differs between them — where the content comes
 * from, and what a change means — is supplied by the caller.
 *
 * @param lastDispatchedHtml the HTML the session starts from, normally the
 *   loaded content normalised through [RichTextState.toHtml]. Every dispatch is
 *   compared against it, so the load itself is never echoed back as an edit.
 * @param onBodyChange called with the editor id and the HTML whenever the
 *   content changes after the initial load.
 * @param id the editor's identity — a note id or a task id.
 */
@Stable
class EditorSession(
    val richTextState: RichTextState,
    private var lastDispatchedHtml: String,
    private val onBodyChange: (id: String, html: String) -> Unit,
    private val id: String,
) {
    /**
     * Replaces the content the editor holds with content that came from outside.
     *
     * The editor owns its content, so a rewrite from another screen, another
     * device or a proposal reaches the editor only as a request to take the new
     * content. The baseline moves with it: the new content is what the upstream
     * already holds, so dispatching it back would be an echo, not an edit.
     */
    fun replaceContent(html: String) {
        richTextState.setHtml(html)
        lastDispatchedHtml = richTextState.toHtml()
    }

    /** Dispatches the current HTML to the owner if it differs from the baseline. */
    fun dispatchHtml() {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(id, html)
        }
    }

    /**
     * Puts a link into the editor.
     *
     * The library's own link insertion refuses a collapsed selection, so the
     * link text is written at the caret first and the link is applied to the
     * range that text occupies. A selection keeps the library's behaviour: when
     * it is already a link the library's [RichTextState.updateLink] replaces the
     * address and keeps the visible text; otherwise the selected text becomes
     * the link.
     *
     * @param url the address the link points at.
     * @param text the text the link shows.
     */
    fun insertLink(
        url: String,
        text: String,
    ) {
        val state = richTextState
        if (!state.selection.collapsed) {
            if (state.isLink) {
                state.updateLink(url = url)
            } else {
                state.addLinkToSelection(url = url)
            }
        } else {
            val caret = state.selection.min
            state.addTextAfterSelection(text)
            state.addLinkToTextRange(url, TextRange(caret, caret + text.length))
        }
        dispatchHtml()
    }
}
