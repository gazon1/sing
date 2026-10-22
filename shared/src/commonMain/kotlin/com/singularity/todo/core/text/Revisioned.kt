package com.singularity.todo.core.text

import androidx.compose.runtime.Immutable

/**
 * A value the upstream owns, and the count of the times it was replaced.
 *
 * ## Why the count lives inside the value
 *
 * An owner that holds a value and a counter in two fields can lie, and the lie is
 * silent: it replaces the value and leaves the counter where it was. The field reads a
 * count that did not move, so it treats the replacement as an echo of its own last push
 * and keeps the text the user typed. Nothing fails; the field just stops listening.
 *
 * The count and the value are two facts about the same thing, so they belong in one
 * object. Changing the value means saying *how* it changed, and there is no way to
 * change it without the count following.
 *
 * ## The two ways a value changes
 *
 * - [typed] — the field's own change: a keystroke. The count does not move, because a
 *   keystroke is the owner's own change and needs no announcement.
 * - [replacedBy] — a replacement from outside. **The count moves.** It is the only way
 *   to move it, and that is the point of the type: a rewrite that does not say so
 *   cannot be written down.
 *
 * The echo of the owner's own save changes nothing: the value is what it already was,
 * so the count stays where it is without anything being said about it.
 *
 * ## Why the constructor is private
 *
 * A constructor that takes a value and a count is a way to write down a count that does
 * not match the value — the very lie this type exists to prevent. The only way to make
 * a new [Revisioned] is to say what kind of change it is.
 *
 * @param value the value as it is now.
 * @param revision how many times the value was replaced from outside.
 */
@Immutable
class Revisioned<T> private constructor(val value: T, val revision: Long) {

    /** The value after the field's own change. The count does not move. */
    fun typed(newValue: T): Revisioned<T> = Revisioned(newValue, revision)

    /** The value after a replacement from outside. The count moves. */
    fun replacedBy(newValue: T): Revisioned<T> = Revisioned(newValue, revision + 1)

    /**
     * The value after a replacement from outside — but only if the value actually changed.
     *
     * An upstream that rewrites a *different* field of the same owner rewrites nothing
     * here, and a count that moved for it would announce a replacement that did not
     * happen: the field would be told that the value it shows was replaced from outside
     * when it was not.
     *
     * @param newValue the value the upstream now holds.
     * @return the value with a moved count if it changed, this value otherwise.
     */
    fun replacedIfChangedBy(newValue: T): Revisioned<T> =
        if (value == newValue) this else replacedBy(newValue)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Revisioned<*>) return false
        return revision == other.revision && value == other.value
    }

    override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + revision.hashCode()

    override fun toString(): String = "Revisioned(value=$value, revision=$revision)"

    companion object {
        /**
         * A value that has not been replaced yet.
         *
         * The first value an owner holds is not a replacement of anything: there is
         * nothing to replace it with, and the field's first sight of it is a seed, not
         * a rewrite.
         */
        fun <T> initial(value: T): Revisioned<T> = Revisioned(value, 0L)
    }
}
