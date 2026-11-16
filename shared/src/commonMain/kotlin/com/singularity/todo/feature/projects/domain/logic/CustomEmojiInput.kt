package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.emoji.Emoji

/**
 * What the project icon picker's custom field turns the typed text into.
 *
 * The field takes free text, and the icon is whatever non-blank text the user
 * typed: the value is trimmed at both ends and kept verbatim from there on.
 * There is no emoji validation, no Unicode normalization and no catalog lookup,
 * so a composed sequence — a ZWJ person, a family, a flag, a skin-tone
 * modifier, a variation selector, a keycap — reaches the icon as the exact
 * code points it was typed as, and text the catalog does not know is a value
 * like any other. The icon is "emoji or nothing" in the sense of ADR
 * `2026-11-14-project-icons-are-emoji-or-nothing`: a non-blank string is a
 * value, and the picker never converts one into another.
 *
 * Blank input is not a value: it picks nothing at all. The sheet stays open,
 * the field keeps what was typed, and nothing is stored — there is no icon to
 * remember and no icon to fall back from.
 */
fun customEmojiFromInput(raw: String): Emoji? {
    val trimmed = raw.trim()
    return if (trimmed.isEmpty()) null else Emoji(trimmed)
}
