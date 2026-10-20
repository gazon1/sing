package com.singularity.todo.core.ui.components

import androidx.compose.foundation.text.input.InputTransformation

/*
 * The filtering rules the text fields apply to what the user types.
 *
 * They are pure functions on purpose. The `InputTransformation` that applies them runs inside the
 * IME commit, where a `TextFieldBuffer` is not constructible from a test — but the *rule* is
 * where the behaviour lives, and the rule has to be testable. The buffer is the delivery
 * mechanism; this is the decision.
 *
 * The named transformations at the bottom of the file are the thin adapters that carry a rule
 * into a text field. Each one computes the decision from the text the buffer holds and then
 * applies at most one edit to it: either the text is rewritten, or the whole edit is rejected.
 */

/**
 * Keeps only the digits of [text], and at most [maxDigits] of them.
 *
 * Used by the fields that accept a number of minutes: a non-digit is dropped rather than
 * rejected, so the user can type into the field without the keyboard fighting them.
 *
 * @param text the text the user has typed.
 * @param maxDigits the maximum number of digits to keep. Unlimited by default, which is
 * what the minutes field wants: it filters characters, it does not cap the number.
 * @return [text] with every non-digit removed and truncated to [maxDigits] characters.
 */
internal fun filterDigits(text: String, maxDigits: Int = Int.MAX_VALUE): String =
    text.filter { it.isDigit() }.take(maxDigits)

/**
 * True when [text] is short enough for a field that accepts at most [maxLength] characters.
 *
 * The transformation that uses it *rejects* the whole edit rather than truncating it, which is
 * what the old `onValueChange` guard did: the field snapped back to the last valid value
 * instead of silently cutting the input short.
 *
 * @param text the text the user has typed.
 * @param maxLength the maximum number of characters the field accepts.
 * @return `true` when the text may be kept.
 */
internal fun isWithinLength(text: String, maxLength: Int): Boolean = text.length <= maxLength

/**
 * True when [text] is a valid recurrence interval — a whole number of at least one.
 *
 * @param text the text the user has typed.
 * @return `true` when the text may be committed as an interval.
 */
internal fun isValidRecurrenceInterval(text: String): Boolean =
    (text.toIntOrNull() ?: 0) > 0

/**
 * True when [text] is a valid day of the month — a whole number between 1 and 31.
 *
 * @param text the text the user has typed.
 * @return `true` when the text may be committed as a day of the month.
 */
internal fun isValidDayOfMonth(text: String): Boolean = (text.toIntOrNull() ?: 0) in 1..31

/**
 * The digits-only form of a recurrence interval, or `null` when the edit has to be rejected.
 *
 * The rule runs in two steps, in this order: the text is reduced to at most [TWO_DIGITS]
 * digits, and the *reduced* text is what [isValidRecurrenceInterval] judges. The order is
 * part of the behaviour — a non-digit is dropped rather than rejected, so a stray letter or
 * minus sign never costs the user the digits that were already in the field.
 *
 * @param text the text the user has typed.
 * @return the text to keep, or `null` when the edit must be rejected outright.
 */
internal fun filterRecurrenceInterval(text: String): String? =
    filterDigits(text, maxDigits = TWO_DIGITS)
        .takeIf { isValidRecurrenceInterval(it) }

/**
 * The digits-only form of a day of the month, or `null` when the edit has to be rejected.
 *
 * As with [filterRecurrenceInterval], the digits are filtered first and the range is checked
 * on the filtered text — so "1a" is a valid day with the letter dropped, while "32" is
 * rejected and the field snaps back to its last valid value.
 *
 * @param text the text the user has typed.
 * @return the text to keep, or `null` when the edit must be rejected outright.
 */
internal fun filterDayOfMonth(text: String): String? =
    filterDigits(text, maxDigits = TWO_DIGITS)
        .takeIf { isValidDayOfMonth(it) }

/**
 * A transformation that keeps only the digits of the input, at most [maxDigits] of them.
 *
 * A non-digit is dropped rather than rejected, so the field can be typed into without the
 * keyboard fighting the user. The text is rewritten with the digits that survived; the
 * digits that were already there are never touched.
 *
 * @param maxDigits the maximum number of digits to keep. Unlimited by default, which is
 * what the minutes field wants: it filters characters, it does not cap the number.
 */
internal fun digitsOnlyTransformation(maxDigits: Int = Int.MAX_VALUE): InputTransformation =
    InputTransformation {
        val current = asCharSequence().toString()
        val filtered = filterDigits(current, maxDigits)
        if (filtered != current) {
            replace(0, length, filtered)
        }
    }

/**
 * A transformation that rejects any input that would make the text longer than [maxLength].
 *
 * The whole edit is rejected rather than truncated: the field snaps back to the last valid
 * value instead of silently cutting the input short, which is what the old `onValueChange`
 * guard did.
 *
 * @param maxLength the maximum number of characters the field accepts.
 */
internal fun maxLengthTransformation(maxLength: Int): InputTransformation =
    InputTransformation {
        if (!isWithinLength(asCharSequence().toString(), maxLength)) {
            revertAllChanges()
        }
    }

/**
 * A transformation for the recurrence interval field: at most two digits, and a whole number
 * of at least one.
 *
 * The digits are filtered first and the range is checked on the filtered text, so a non-digit
 * is dropped instead of rejecting the whole edit. An edit whose filtered text is not a valid
 * interval is rejected outright — the field snaps back to the last valid value.
 */
internal fun recurrenceIntervalTransformation(): InputTransformation =
    InputTransformation {
        val current = asCharSequence().toString()
        val kept = filterRecurrenceInterval(current)
        if (kept == null) {
            revertAllChanges()
        } else if (kept != current) {
            replace(0, length, kept)
        }
    }

/**
 * A transformation for the day-of-month field: at most two digits, and a whole number
 * between 1 and 31.
 *
 * As in [recurrenceIntervalTransformation], the digits are filtered first and the range is
 * checked on the filtered text; an edit whose filtered text is not a valid day of the month
 * is rejected outright.
 */
internal fun dayOfMonthTransformation(): InputTransformation =
    InputTransformation {
        val current = asCharSequence().toString()
        val kept = filterDayOfMonth(current)
        if (kept == null) {
            revertAllChanges()
        } else if (kept != current) {
            replace(0, length, kept)
        }
    }

/**
 * Both the recurrence interval and the day of the month are two-digit fields — 99 days, 99
 * occurrences. A third digit is truncated rather than rejected.
 */
private const val TWO_DIGITS = 2
