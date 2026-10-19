package com.singularity.todo.core.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The three shapes a text field in this app can have.
 *
 * A state-based field holds its text in a [TextFieldState], and *who* owns that text is
 * the decision that matters. There are three answers, and each has its own helper:
 *
 * | Shape | Who owns the text | Helper |
 * |---|---|---|
 * | **local** | the field itself | `rememberTextFieldState` (Compose's own) |
 * | **bridged** | the field *and* a ViewModel-owned string, in sync | [rememberBridgedTextFieldState] |
 * | **anchored** | an outside value; the field only shows it | [rememberAnchoredTextFieldState] |
 *
 * **Local** — the field is the only owner. Nothing outside reads the text and nothing
 * outside writes it: a filter field in a sheet that only matters while the sheet is
 * open, a search field whose result is applied on submit rather than on every
 * keystroke. Compose's `rememberTextFieldState` is the whole of it.
 *
 * **Bridged** — the field and a string somewhere else (a ViewModel, a UI state) hold
 * the same text and have to agree. This is the shape most fields in the app have, and
 * the one that needs the most care: the two sides update at different moments, and
 * neither may overwrite the other's newer value. [rememberBridgedTextFieldState] owns
 * that rule.
 *
 * **Anchored** — the text comes from outside and the field cannot be edited: a
 * dropdown that shows the current provider, a time that opens a picker instead of a
 * keyboard. The field is a display, and its only job is to show the value it is
 * anchored to, including when that value changes underneath it.
 */
@androidx.compose.runtime.Composable
fun rememberAnchoredTextFieldState(text: String): TextFieldState {
    val state = rememberTextFieldState(initialText = text)
    // The anchored value can change under the field — the provider is reloaded, the
    // timezone changes, the model list is refreshed. The field follows it, and only
    // when it actually differs: writing the same text again would reset the cursor
    // and, on a field that is not read-only, undo a selection the user made.
    LaunchedEffect(text) {
        if (state.text.toString() != text) {
            state.setTextAndPlaceCursorAtEnd(text)
        }
    }
    return state
}
