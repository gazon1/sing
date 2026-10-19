package com.singularity.todo.core.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

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
 * Decides whether the field's text must be pushed when the field leaves the composition.
 *
 * Two conditions, and the second is the one that matters:
 *
 * - The upstream must not already hold the text — otherwise there is nothing to push.
 * - The upstream must be *behind*: its value must be one the field pushed. An upstream
 *   that changed from the outside — a discarded draft, a cleared query — is not behind,
 *   it is ahead, and the text on screen is the text that was discarded. Pushing it back
 *   would resurrect what the user threw away.
 *
 * @param fieldText the text the field currently holds.
 * @param externalText the upstream value.
 * @param pushedValues the texts the field pushed upstream, oldest first.
 * @return `true` when the field's text must be pushed on the way out.
 */
internal fun shouldFlushOnDispose(
    fieldText: String,
    externalText: String,
    pushedValues: List<String>,
): Boolean = shouldPushFieldText(fieldText, externalText) && externalText in pushedValues

/**
 * What the bridge remembers between emissions.
 *
 * Plain fields, not snapshot state: nothing composes from these values, they are only
 * read by the bridge's own coroutines, and a snapshot write per keystroke is work the
 * frame does not need.
 */
private class BridgeHistory(initialText: String) {

    /** The last text the field held, seed included. */
    var lastEmitted: String = initialText

    /** The texts the field pushed upstream, oldest first, bounded. */
    val pushedValues: MutableList<String> = mutableListOf()

    /** Records a text the field handed out, dropping the oldest when the bound is hit. */
    fun recordPush(text: String) {
        pushedValues += text
        while (pushedValues.size > RECENT_PUSH_LIMIT) {
            pushedValues.removeAt(0)
        }
    }
}

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
 * The last edit is flushed when the field leaves the composition. The push is asynchronous —
 * a `snapshotFlow` emission lands on the next frame — so a user who types and navigates away
 * in the same frame would otherwise leave the upstream one character behind.
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
    val currentOnTextChanged by rememberUpdatedState(onTextChanged)

    val history = remember { BridgeHistory(externalText) }

    // field -> upstream
    LaunchedEffect(state) {
        fieldTextFlow(state)
            .collect { text ->
                // Every text the field holds is recorded, including the seed it started
                // with: the pull guard below compares the upstream against the last text
                // the field held, and the seed is one of them.
                history.lastEmitted = text
                if (!shouldPushFieldText(text, currentExternal)) return@collect
                history.recordPush(text)
                currentOnTextChanged(text)
            }
    }

    // upstream -> field (only when the change did not come from us)
    LaunchedEffect(externalText) {
        if (shouldPullExternalText(externalText, history.lastEmitted, state.text.toString(), history.pushedValues)) {
            state.setTextAndPlaceCursorAtEnd(externalText)
        }
    }

    // The last edit must not be lost when the field leaves the composition. The push above
    // is asynchronous — the emission lands on the next frame — so a user who types and
    // navigates away in the same frame leaves the upstream one character behind.
    //
    // The flush is guarded by the upstream being *behind*: its value must be one the
    // field pushed. An upstream that changed from the outside — a discarded draft, a
    // cleared query — is not behind, it is ahead, and the text on screen is the text
    // that was discarded. Pushing it back would resurrect what the user threw away.
    //
    // The price is one character in the rarest case: a field whose upstream has not
    // received *any* push yet (the user typed one character and left in the same
    // frame) has an upstream value the field never pushed, and the flush declines.
    DisposableEffect(state) {
        onDispose {
            val text = state.text.toString()
            history.lastEmitted = text
            if (!shouldFlushOnDispose(text, currentExternal, history.pushedValues)) return@onDispose
            history.recordPush(text)
            currentOnTextChanged(text)
        }
    }

    return state
}
