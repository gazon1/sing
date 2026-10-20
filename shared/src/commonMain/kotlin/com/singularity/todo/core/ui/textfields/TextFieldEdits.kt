package com.singularity.todo.core.ui.textfields

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The text a state-based field holds, as a flow of the distinct values it takes.
 *
 * This is the single observation of a field's text in the app. Both the bridge — which
 * pushes the field's text upstream — and [ObserveTextFieldEdits] — which reports that
 * the user edited it — read the field through it, so the two can never disagree about
 * what "the text changed" means. In particular they agree on the *seed*: the first
 * value this flow emits is the text the field started with, not an edit.
 *
 * @param state the field to observe.
 * @return the field's text, oldest first.
 */
internal fun fieldTextFlow(state: TextFieldState): Flow<String> =
    snapshotFlow { state.text.toString() }.distinctUntilChanged()

/**
 * Invokes [onEdited] whenever the user changes the text of [state].
 *
 * A state-based field has no `onValueChange` parameter, so a caller that wants to react to an
 * edit — clearing a stale error message, dropping a transient notice — observes the field's text
 * instead. This is that observation, and it is the only place in the app that needs it: the
 * "clear the error when the user starts typing" behaviour that the value-based API got for free
 * from its `onValueChange` callback.
 *
 * The first emission is skipped: it carries the text the field was seeded with, not an edit, and
 * reacting to it would clear an error that was raised for a reason the user has not touched yet.
 *
 * @param state the field to observe.
 * @param onEdited invoked on every edit of the field's text.
 */
@Composable
fun ObserveTextFieldEdits(state: TextFieldState, onEdited: () -> Unit) {
    val callback by rememberUpdatedState(onEdited)
    LaunchedEffect(state) {
        fieldTextFlow(state)
            .drop(1)
            .collect { callback() }
    }
}
