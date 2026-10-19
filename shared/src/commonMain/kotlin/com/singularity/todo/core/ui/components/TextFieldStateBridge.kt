package com.singularity.todo.core.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * The number of pushed values the bridge remembers.
 *
 * The history exists to recognise a *lagging* upstream — one that echoes a value the
 * field pushed a moment ago. A handful of values covers the window in which an
 * upstream can be behind, and the bound keeps a long typing session from growing the
 * list without end.
 */
private const val RECENT_PUSH_LIMIT = 16

/**
 * Decides whether an upstream value must overwrite the text a field currently holds.
 *
 * The upstream may lag behind what the user has already typed. A value the upstream
 * hands back that the field itself produced is an echo and must be ignored; a value
 * that differs from anything the field produced comes from outside the field and must
 * be applied.
 *
 * The naive alternative — "overwrite whenever the upstream differs from the field" —
 * live-locks: while the upstream is still catching up, it holds the *previous* text,
 * so the comparison passes and the keystrokes the user just made are reverted. Guarding
 * on the values the field handed out is what keeps the field's text and the upstream's
 * copy in agreement without fighting the user.
 *
 * The values the field handed out are two different things, and the difference matters:
 *
 * - The **last value the field held** — the text it displays right now, including the
 *   seed it started with. A field that was seeded and never edited handed out its
 *   seed, so an upstream that later clears its value is a change from outside the
 *   field and must be applied. A guard on the last *pushed* value would compare the
 *   cleared value against the empty string the field never pushed, call it an echo,
 *   and leave the stale text on screen.
 * - The **values the field pushed** — the texts it actually sent upstream. These are
 *   the echoes to recognise. A value the field pushed and then no longer holds is an
 *   upstream that is *behind*, not one that is rewriting: applying it would undo the
 *   user's most recent edit with a value the app already moved past.
 *
 * The trade-off this buys: an upstream that *deliberately* rewrites the field to a
 * value the field pushed earlier is not applied. The two cases are indistinguishable
 * from the field's side — both arrive as "a value I sent a moment ago" — and the
 * stale case is the one that destroys work, so it wins. A deliberate rewrite to a
 * value the field no longer holds is rare; a lagging upstream is not.
 *
 * @param externalText the upstream value.
 * @param lastEmitted the last text the field held.
 * @param currentText the text the field currently holds.
 * @param pushedValues the texts the field pushed upstream, oldest first.
 * @return `true` when [externalText] must be written into the field.
 */
internal fun shouldPullExternalText(
    externalText: String,
    lastEmitted: String,
    currentText: String,
    pushedValues: List<String> = emptyList(),
): Boolean = externalText != lastEmitted &&
    externalText != currentText &&
    pushedValues.none { it == externalText }

/**
 * Decides whether the field's text must be pushed upstream.
 *
 * A value the upstream already holds is an echo — the upstream's own copy coming back
 * around — and pushing it again would mark the field dirty for no reason. The field's
 * *initial* text is such an echo: it is seeded from the upstream, so pushing it would
 * write the upstream's own value back to it.
 *
 * @param fieldText the text the field currently holds.
 * @param externalText the upstream value.
 * @return `true` when [fieldText] must be pushed upstream.
 */
internal fun shouldPushFieldText(fieldText: String, externalText: String): Boolean =
    fieldText != externalText

/**
 * Owns the text of a state-based text field and keeps a ViewModel-owned string in sync.
 *
 * The field is the source of truth while the user types: edits are pushed to [onTextChanged]
 * (typically `viewModel.onIntent(...)`). Values that arrive from outside the field — a re-seeded
 * draft, a title rewritten by the AI, a query cleared by the ViewModel — are pulled back into the
 * field.
 *
 * The pull is guarded by the values this helper pushed, so a slow or debounced upstream can
 * never overwrite what the user is currently typing, and a lagging upstream that echoes an
 * older push cannot undo the user's latest edit either. The push is guarded the other way
 * round: a value the upstream already holds is an echo and is not written back.
 *
 * The state lives in the composable, not in the ViewModel: a `TextFieldState` is a plain holder,
 * but the ViewModel's contract in this project is a single immutable UI state, and a mutable
 * buffer inside it would be a second source of truth for the same value.
 *
 * @param externalText the upstream (ViewModel) value.
 * @param onTextChanged invoked with the field text whenever the user edits it.
 * @return the [TextFieldState] to hand to the text field.
 */
@Composable
fun rememberBridgedTextFieldState(
    externalText: String,
    onTextChanged: (String) -> Unit,
): TextFieldState {
    // Seeded from the upstream: a field that starts empty while the upstream holds a loaded value
    // would push that empty value back on its first emission and wipe what was loaded.
    val state = rememberTextFieldState(initialText = externalText)
    val currentExternal by rememberUpdatedState(externalText)

    // The last text the field held, seed included.
    var lastEmitted by remember { mutableStateOf(externalText) }

    // The texts the field pushed upstream, oldest first, bounded.
    var pushedValues by remember { mutableStateOf(emptyList<String>()) }

    // field -> upstream
    LaunchedEffect(state) {
        fieldTextFlow(state)
            .collect { text ->
                // Every text the field holds is recorded, including the seed it started
                // with: the pull guard below compares the upstream against the last text
                // the field held, and the seed is one of them.
                lastEmitted = text
                if (!shouldPushFieldText(text, currentExternal)) return@collect
                pushedValues = (pushedValues + text).takeLast(RECENT_PUSH_LIMIT)
                onTextChanged(text)
            }
    }

    // upstream -> field (only when the change did not come from us)
    LaunchedEffect(externalText) {
        if (shouldPullExternalText(externalText, lastEmitted, state.text.toString(), pushedValues)) {
            state.setTextAndPlaceCursorAtEnd(externalText)
        }
    }

    return state
}
