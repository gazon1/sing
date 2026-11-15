package com.singularity.todo.feature.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.mohamedrejeb.richeditor.model.RichTextState
import com.singularity.todo.core.ui.rich.EditorSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Creates an [EditorSession] keyed to [state.id].
 * A fresh [RichTextState] is created for each note id, preventing stale state
 * when the user switches between notes.
 */
@Composable
fun rememberEditorSession(
    state: EditorState.Editing,
    onBodyChange: (id: String, html: String) -> Unit,
): EditorSession {
    val onBodyChangeState = rememberUpdatedState(onBodyChange)

    val session =
        remember(state.id) {
            val richTextState = RichTextState().also { it.setHtml(state.bodySource.text) }
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
    // The count travels with the body, in a `Revisioned`: the first revision is the
    // load itself, which the session already has, and the guard is there so the
    // content is not set twice on the first composition. The body's own count is
    // what says so — the title's count belongs to the title, and a rewrite of the
    // title alone has nothing to do with the content of the body.
    LaunchedEffect(state.id, state.bodySource.revision) {
        if (state.bodySource.revision == 0L) return@LaunchedEffect
        session.replaceContent(state.bodySource.text)
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
