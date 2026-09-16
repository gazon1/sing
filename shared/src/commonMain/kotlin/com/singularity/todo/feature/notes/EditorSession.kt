package com.singularity.todo.feature.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Holds all mutable editor state for one note-editing session.
 *
 * Stored at the screen level (not inside the Composable hierarchy) so both
 * [EditorTitleAndBody] and [EditorToolbar][com.singularity.todo.feature.notes.components.EditorToolbar]
 * can share the same [RichTextState] — the toolbar can live in
 * [androidx.compose.material3.Scaffold.bottomBar] without passing state down
 * through intermediate composables.
 *
 * Keyed to [state.id] so switching notes creates a fresh session automatically.
 *
 * @param onBodyChange Called whenever the HTML changes after the initial load.
 *                     The initial load is skipped to avoid echoing the loaded
 *                     content back to the ViewModel as a phantom edit.
 */
class EditorSession(
    val richTextState: RichTextState,
    var titleFieldValue: String,
    private var lastDispatchedHtml: String,
    private var firstLoadSkipped: Boolean,
    private val onBodyChange: (id: String, html: String) -> Unit,
    private val id: String,
) {
    /**
     * All links inserted in this session, mapped to their character ranges.
     * Used by [findLinkAt] to resolve link taps to URLs.
     *
     * Populated by [recordLink] and consumed by [findLinkAt].
     * Note: for link resolution after PR #2, prefer walking
     * [RichTextState.children] ordered spans instead of this in-memory map.
     */
    private val insertedLinks = mutableListOf<Pair<IntRange, String>>()

    /**
     * Records a link insertion for later tap-resolution.
     * When a selection is collapsed (cursor only), no meaningful range can be
     * stored — the link will not be resolvable by tap alone. The caller
     * should position the cursor inside the link text before tapping.
     */
    fun recordLink(url: String) {
        val sel = richTextState.selection
        insertedLinks.add(sel.min..<sel.max to url)
    }

    /**
     * Finds the URL of a link at the given character offset.
     * Returns null when the offset falls outside any recorded link range.
     */
    fun findLinkAt(charOffset: Int): String? = insertedLinks.find { (range, _) ->
        charOffset in range
    }?.second

    /**
     * Dispatches the current HTML to the ViewModel if it differs from what
     * was last dispatched. The [firstLoadSkipped] guard skips the very first
     * dispatch, which would otherwise echo the just-loaded content back as
     * a phantom user edit.
     */
    fun dispatchHtml() {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(id, html)
        }
    }

    fun notifyFirstLoadSkipped() {
        firstLoadSkipped = true
    }

    val isFirstLoadSkipped: Boolean get() = firstLoadSkipped
}

/**
 * Creates [EditorSession] keyed to [state.id].
 * A fresh [RichTextState] is created for each note id, preventing stale state
 * when the user switches between notes.
 */
@Composable
fun rememberEditorSession(
    state: EditorState.Editing,
    onBodyChange: (id: String, html: String) -> Unit,
): EditorSession {
    val richTextState = remember(state.id) {
        RichTextState().also { it.setHtml(state.html) }
    }

    val session = remember(state.id) {
        EditorSession(
            richTextState = richTextState,
            titleFieldValue = state.title,
            lastDispatchedHtml = state.html,
            firstLoadSkipped = false,
            onBodyChange = onBodyChange,
            id = state.id,
        )
    }

    // Sync title from external state changes (e.g. AI improve replacing the title)
    LaunchedEffect(state.title) {
        session.titleFieldValue = state.title
    }

    // Dispatch HTML to ViewModel on every mutation.
    // The LaunchedEffect re-triggers whenever richTextState changes (i.e. on every
    // keystroke), so we don't need an explicit onValueChange callback here.
    LaunchedEffect(state.id, richTextState) {
        if (!session.isFirstLoadSkipped) {
            session.notifyFirstLoadSkipped()
            return@LaunchedEffect
        }
        session.dispatchHtml()
    }

    return session
}
