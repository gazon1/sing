package com.singularity.todo.feature.projects.domain.logic

/**
 * How many recently used emoji the project icon picker keeps.
 *
 * Ten is enough to cover the emoji a person actually reaches for and short
 * enough that the row still fits the sheet.
 */
const val RECENT_EMOJI_LIMIT = 10

/**
 * The history after [emoji] was picked: the picked emoji goes to the front, an
 * older copy of the same emoji is dropped, and the list is bounded to [limit].
 *
 * The emoji is one opaque string. A composed sequence — a ZWJ family, a flag, a
 * skin-tone modifier, a variation selector — is a single value, not several, so
 * it is compared and stored as the whole string.
 *
 * A blank [emoji] changes nothing: there is no glyph to remember, and the
 * history is not a record of "nothing was picked".
 */
fun rememberRecentEmoji(
    current: List<String>,
    emoji: String,
    limit: Int = RECENT_EMOJI_LIMIT,
): List<String> {
    if (emoji.isBlank()) return current.take(limit)
    return (listOf(emoji) + current.filter { it != emoji }).take(limit)
}
