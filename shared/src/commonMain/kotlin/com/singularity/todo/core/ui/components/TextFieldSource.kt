package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Immutable

/**
 * A value a bridged text field shows, and the authority the upstream has over it.
 *
 * ## The two shapes are the two answers
 *
 * A field's upstream can replace the value in two ways, and the difference is
 * behavioural, not stylistic:
 *
 * - **[StaticText]** — the upstream replaces the value only with one the field
 *   never produced: it loads the value once, or it clears it. The field's echo
 *   guard needs no lifting, because the field cannot have produced the new
 *   value.
 * - **[OwnedText]** — the upstream owns the value: it may replace it with
 *   anything, including a value the field itself produced earlier. The owner
 *   counts the replacements, and the field reads the count. A rewrite that
 *   lands on a value the field produced is indistinguishable from a lagging
 *   upstream — from the field's side both arrive as "a value I sent a moment
 *   ago" — so the owner has to say so, and a counter is the smallest thing
 *   that carries it.
 *
 * ## Why this is a type and not a rule in a table
 *
 * The question "who may rewrite this field?" used to be answered in a
 * registry: a table of every bridged field site and the answer for each, plus
 * a test that read the *source text* of every call site to check the table.
 * That arrangement had three failure modes, and all three were silent:
 *
 * - a field whose upstream owns the value passed no counter, and the rewrite
 *   was dropped when it landed on a value the field had produced;
 * - a field passed a counter that never moved — `revision = 0` — and the
 *   registry said it was fine, because a constant is a number;
 * - a field forgot the whole question, because the parameter that carried the
 *   answer had a default.
 *
 * A type removes all three. The counter is not a parameter any more: it is a
 * property of the source, and the source is built by the component that owns
 * the value — the only component that knows the answer. A field that receives
 * an [OwnedTextSource] cannot be wired as if the value were static: the two
 * are different types, and the bridge has an overload for each. A field cannot
 * freeze the counter, because the counter is not something a field can write
 * down.
 *
 * The remaining question — *which* upstream owns this value — stays prose,
 * because it is a fact about the world and not about the types. It lives in the
 * rewrite table of `.agents/skills/singularity-todo-text-field-states/SKILL.md`,
 * and the source shape in that table is the type the code uses.
 *
 * ## How a field gets its source
 *
 * The owner builds the source; the field passes it through. A field never
 * builds one for itself: that would be the field answering a question it
 * cannot know the answer to.
 *
 * @see rememberBridgedTextFieldState
 */
sealed interface TextSource {

    /** The text the field shows. */
    val text: String
}

/**
 * A value that the upstream replaces only with values the field never produced.
 *
 * The upstream loads the value once, or clears it. Either way the field cannot
 * have produced the new text, so the bridge's echo guard — which exists to
 * recognise a *lagging* upstream — has nothing to recognise and stays as it
 * is.
 */
sealed interface StaticTextSource : TextSource

/**
 * A value the upstream owns: it may replace the text with anything.
 *
 * The owner counts the replacements. The count is what tells the field that the
 * value it is handed is a *rewrite* rather than the echo of a value the field
 * pushed earlier.
 */
sealed interface OwnedTextSource : TextSource {

    /**
     * Counts the replacements of the value.
     *
     * It advances when the owner *replaces* the content, and never for a
     * keystroke or for the echo of the owner's own save. Any change to it means
     * "the value that comes with it is a rewrite".
     */
    val revision: Long
}

/**
 * A static source: a value the upstream loads once, or clears.
 *
 * @param text the text the field shows.
 */
@Immutable
data class StaticText(override val text: String) : StaticTextSource

/**
 * An owned source: a value the upstream may replace with anything.
 *
 * @param text the text the field shows.
 * @param revision the count of the replacements of the value.
 */
@Immutable
data class OwnedText(override val text: String, override val revision: Long) : OwnedTextSource
