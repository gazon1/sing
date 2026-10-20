package com.singularity.todo.feature.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
 * @param lastDispatchedHtml The HTML the session starts from — the *loaded* content,
 *   normalised through [RichTextState.toHtml]. Every dispatch is compared against
 *   it, so the load itself is never echoed back as a phantom edit.
 * @param onBodyChange Called whenever the HTML changes after the initial load.
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
     * The editor owns its content, so a note rewritten elsewhere — a proposal
     * applied to it, a sync from another device — reaches the editor only as a
     * request to take the new content. The baseline moves with it: the new
     * content is what the upstream already holds, so dispatching it back would be
     * an echo of the upstream's own value, and would mark the draft dirty for
     * nothing.
     */
    fun replaceContent(html: String) {
        richTextState.setHtml(html)
        lastDispatchedHtml = richTextState.toHtml()
    }

    /**
     * Dispatches the current HTML to the ViewModel if it differs from what
     * was last dispatched.
     */
    fun dispatchHtml() {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(id, html)
        }
    }
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
    val onBodyChangeState = rememberUpdatedState(onBodyChange)

    val session = remember(state.id) {
        val richTextState = RichTextState().also { it.setHtml(state.html) }
        EditorSession(
            richTextState = richTextState,
            // The baseline is the loaded content *as this editor renders it* —
            // `state.html` may differ from `toHtml()` in normalisation only.
            // Comparing against the rendered form is what makes the first
            // observation a no-op instead of a phantom edit.
            lastDispatchedHtml = richTextState.toHtml(),
            onBodyChange = { id, html -> onBodyChangeState.value(id, html) },
            id = state.id,
        )
    }

    // The note was rewritten from outside: take the content it now holds.
    //
    // The revision is the editor's signal that the content it shows was replaced
    // rather than typed — a proposal applied to the note, a sync from another
    // device. Without it the editor would keep the old text, and the next
    // keystroke would dispatch that old text over the new one.
    //
    // The count travels with the title, in a `Revisioned`: the first revision is the
    // load itself, which the session already has, and the guard is there so the
    // content is not set twice on the first composition.
    LaunchedEffect(state.id, state.title.revision) {
        if (state.title.revision == 0L) return@LaunchedEffect
        session.replaceContent(state.html)
    }

    // Dispatch the HTML to the ViewModel on every edit.
    //
    // The editor's text lives *inside* the state object, and the state object is the
    // same instance for the whole session — it has identity equality, so a
    // `LaunchedEffect` keyed on it starts once and never restarts. The effect has to
    // *read* the state to see the change: `snapshotFlow` re-emits whenever the text
    // it reads changes.
    //
    // There is no "skip the first emission" guard: the first emission is the loaded
    // content, and [EditorSession.dispatchHtml] compares it against the loaded
    // baseline, so it dispatches nothing. A positional guard (`drop(1)`) would be
    // wrong here — an edit made before the flow's first emission is a real edit,
    // and dropping the first emission drops it with the phantom.
    LaunchedEffect(state.id, session) {
        snapshotFlow { session.richTextState.annotatedString }
            .map { session.richTextState.toHtml() }
            .distinctUntilChanged()
            .collect { session.dispatchHtml() }
    }

    return session
}
