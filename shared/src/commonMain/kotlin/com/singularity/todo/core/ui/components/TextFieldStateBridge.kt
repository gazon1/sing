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
 * stale case is the one that destroys work, so it wins.
 *
 * The one way out is [isDeliberateRewrite]: an upstream that *knows* it is rewriting
 * says so, and the guard is lifted for that value. See [rememberBridgedTextFieldState]
 * for how the upstream says it.
 *
 * @param externalText the upstream value.
 * @param lastEmitted the last text the field held.
 * @param currentText the text the field currently holds.
 * @param pushedValues the texts the field pushed upstream, oldest first.
 * @param isDeliberateRewrite `true` when the upstream declared this value a rewrite
 *   rather than an echo. The echo guard does not apply to it; the only question left
 *   is whether the field already shows it.
 * @return `true` when [externalText] must be written into the field.
 */
internal fun shouldPullExternalText(
    externalText: String,
    lastEmitted: String,
    currentText: String,
    pushedValues: List<String> = emptyList(),
    isDeliberateRewrite: Boolean = false,
): Boolean = if (isDeliberateRewrite) {
    // A declared rewrite wins over whatever the field holds — but there is nothing to
    // write when the field already shows that text.
    externalText != currentText
} else {
    externalText != lastEmitted &&
        externalText != currentText &&
        pushedValues.none { it == externalText }
}

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
 * **The trade-off is accepted, and it is pinned by a test.** The price is one character
 * in the rarest case: a field whose upstream has received *no* push at all — the user
 * typed one character and left in the same frame — has an upstream value the field never
 * pushed, so the flush declines and that character is lost. The alternative is to push
 * on every dispose, which would resurrect a discarded draft every time a user cleared a
 * field and navigated away. The draft is worth more than the character, and the
 * character is recoverable by typing it again.
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
private class BridgeHistory(initialText: String, initialRevision: Long?) {

    /** The last text the field held, seed included. */
    var lastEmitted: String = initialText

    /** The texts the field pushed upstream, oldest first, bounded. */
    val pushedValues: MutableList<String> = mutableListOf()

    /**
     * The last revision token the bridge saw.
     *
     * A revision that differs from this one is the upstream declaring a rewrite; the
     * token is remembered so the same declaration arriving twice is not read as two
     * rewrites. It starts at the token the field was created with, so the *first*
     * upstream value is never mistaken for a rewrite.
     *
     * `null` means the upstream never declared a rewrite — a [StaticText]. There
     * is no token to compare, and the guard stays exactly as it was.
     */
    var lastRevision: Long? = initialRevision

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
 * ## The source says who may rewrite the field
 *
 * The upstream may replace the value in two ways, and the difference is behavioural: an
 * upstream that only loads or clears the value cannot produce a value the field has produced,
 * while an upstream that *owns* the value can. The second case needs the owner to say so — a
 * rewrite that lands on a value the field pushed earlier is indistinguishable from a lagging
 * upstream — and the way it says so is the revision counter the owner keeps.
 *
 * That is why the source is a **type** and not a parameter with a default. The two shapes are
 * two overloads, so a field that holds an [OwnedText] cannot be wired as if its value
 * were static, and a field that passes a source through cannot freeze the counter: the
 * counter is not a parameter it can write down. Writing one down means *building* the
 * source, and building the source at the field is what the contract forbids.
 *
 * @param source the upstream's value and the authority over it. Built by the owner.
 * @param onTextChanged invoked with the field text whenever the user edits it.
 * @return the [TextFieldState] to hand to the text field.
 * @see TextSource for the two shapes and why the question is a type.
 */
@Composable
fun rememberBridgedTextFieldState(
    source: OwnedText,
    onTextChanged: (String) -> Unit,
): TextFieldState = rememberBridgedTextFieldState(source.text, onTextChanged, source.revision)

/**
 * Owns the text of a state-based text field and keeps a ViewModel-owned string in sync.
 *
 * The upstream replaces the value only with values the field never produced — it loads the
 * value once, or clears it — so the echo guard needs no lifting and there is no counter to
 * read.
 *
 * @param source the upstream's value. Built by the owner.
 * @param onTextChanged invoked with the field text whenever the user edits it.
 * @return the [TextFieldState] to hand to the text field.
 * @see TextSource for the two shapes and why the question is a type.
 */
@Composable
fun rememberBridgedTextFieldState(
    source: StaticText,
    onTextChanged: (String) -> Unit,
): TextFieldState = rememberBridgedTextFieldState(source.text, onTextChanged, null)

/**
 * Owns the text of a state-based text field whose upstream is not known to be one kind or the
 * other.
 *
 * A component that serves two upstreams — the task editor serves the create draft and the
 * detail draft — cannot say which kind of upstream it holds, and must not decide: the source
 * carries the answer, and this overload reads it.
 *
 * The `when` is exhaustive because [TextSource] is sealed with exactly two permitted shapes.
 * A third kind of source cannot be added without the bridge saying what it means — the answer
 * is not a matter of taste.
 *
 * @param source the upstream's value and the authority over it. Built by the owner.
 * @param onTextChanged invoked with the field text whenever the user edits it.
 * @return the [TextFieldState] to hand to the text field.
 */
@Composable
internal fun rememberBridgedTextFieldState(
    source: TextSource,
    onTextChanged: (String) -> Unit,
): TextFieldState = when (source) {
    is OwnedText -> rememberBridgedTextFieldState(source, onTextChanged)
    is StaticText -> rememberBridgedTextFieldState(source, onTextChanged)
}

/**
 * The bridge itself.
 *
 * @param externalText the upstream value.
 * @param onTextChanged invoked with the field text whenever the user edits it.
 * @param revision the owner's count of the replacements, or `null` when the upstream never
 *   declares a rewrite.
 */
@Composable
private fun rememberBridgedTextFieldState(
    externalText: String,
    onTextChanged: (String) -> Unit,
    revision: Long?,
): TextFieldState {
    // Seeded from the upstream: a field that starts empty while the upstream holds a loaded value
    // would push that empty value back on its first emission and wipe what was loaded.
    val state = rememberTextFieldState(initialText = externalText)
    val currentExternal by rememberUpdatedState(externalText)
    val currentOnTextChanged by rememberUpdatedState(onTextChanged)

    val history = remember { BridgeHistory(externalText, revision) }

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
    LaunchedEffect(externalText, revision) {
        // A new revision is the upstream declaring a rewrite. The token is consumed here,
        // so the same declaration arriving twice is not read as two rewrites.
        val isRewrite = revision != null && revision != history.lastRevision
        if (isRewrite) {
            history.lastRevision = revision
        }
        val shouldPull = shouldPullExternalText(
            externalText = externalText,
            lastEmitted = history.lastEmitted,
            currentText = state.text.toString(),
            pushedValues = history.pushedValues,
            isDeliberateRewrite = isRewrite,
        )
        if (shouldPull) {
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
