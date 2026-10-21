package com.singularity.todo.core.ui.textfields

import androidx.compose.runtime.Immutable

/**
 * The text of a field that the field alone writes.
 *
 * ## What the static kind claims, and where the claim lives
 *
 * A **static** source says: *the upstream replaces the value only with one the field
 * never produced.* The bridge's echo guard drops a value that the field itself pushed,
 * because a field that shows what it already shows has nothing to update. That guard is
 * right for an upstream whose writes are echoes — and wrong for an upstream that
 * replaces the value with something the field had produced before, because then the
 * replacement is dropped and the field keeps the text the user typed.
 *
 * So the static kind is a promise about the **upstream's writes**, and a promise that
 * lives in a comment next to a `StaticText` is a promise nobody can check. An owner
 * that holds a bare `String` can write to it from anywhere, and the write says nothing
 * about what it was.
 *
 * This type is where the promise lives. The upstream's writes are **named**:
 *
 * - [seeded] — the upstream loaded a value. It came from outside the field, so the
 *   field never produced it.
 * - [echoed] — the field's own text came back. The field already shows it.
 * - [cleared] — the upstream emptied it.
 *
 * There is no fourth operation, and the constructor is private. An owner of a static
 * field therefore cannot write the value in any other way: every write says what kind
 * of write it was.
 *
 * What the type does **not** do is decide *which* of the three happened — only the
 * owner knows that, and only the owner can say it. The type turns a promise that lived
 * in a sentence into a promise that lives in the shape of the owner's writes, where a
 * reviewer reads it at every call site. A `seeded` that carries a value the field
 * produced is still possible; it is no longer invisible.
 *
 * ## Why not a count
 *
 * The owned kind ([Revisioned]) carries a count of the replacements, because the
 * upstream *does* replace the value and the field has to know. A static upstream does
 * not replace the value, so there is no count to carry: the promise is not "how many
 * times" but "only in these ways".
 *
 * @param text the text as it is now.
 */
@Immutable
class FieldOwnedText private constructor(val text: String) {

    /**
     * The upstream loaded a value.
     *
     * The value came from outside the field — a saved search, the settings on disk, the
     * annotation as it was when the sheet opened — so the field never produced it, and
     * the field takes it as it stands.
     */
    fun seeded(value: String): FieldOwnedText = FieldOwnedText(value)

    /**
     * The field's own text came back.
     *
     * The field already shows it, so there is nothing to update: this is the echo the
     * bridge's guard is there for.
     */
    fun echoed(fieldText: String): FieldOwnedText = FieldOwnedText(fieldText)

    /** The upstream emptied the field. */
    fun cleared(): FieldOwnedText = FieldOwnedText("")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FieldOwnedText) return false
        return text == other.text
    }

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "FieldOwnedText(text=$text)"

    companion object {
        /** A field that holds nothing yet. */
        fun empty(): FieldOwnedText = FieldOwnedText("")
    }
}
