package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.emoji.Emoji

/**
 * What the project icon picker's custom field turns the typed text into.
 *
 * The field takes free text, and the icon is whatever non-blank text the user
 * typed: the value is trimmed at both ends and taken as it is from there on.
 * There is no emoji validation, no Unicode normalization and no catalog lookup,
 * so a composed sequence — a ZWJ person, a family, a flag, a skin-tone
 * modifier, a variation selector, a keycap — reaches the icon as the exact
 * code points it was typed as, and text the catalog does not know is a value
 * like any other. The icon is "emoji or nothing" in the sense of ADR
 * `2026-11-14-project-icons-are-emoji-or-nothing`: a non-blank string is a
 * value, and the picker never converts one into another.
 *
 * The trim is Kotlin's `trim()`, and that is exactly what it promises: the
 * characters it removes are the ones for which `Char.isWhitespace` holds —
 * spaces, tabs, line breaks and the Unicode space separators, a no-break
 * space or an em space among them. Nothing else is whitespace, so nothing else
 * goes: a zero-width space at the edge of the value stays, and so does every
 * character inside it. The contract is not "strip the invisible characters",
 * and this rule is not a Unicode whitespace parser of its own.
 *
 * Blank input is not a value: it picks nothing at all. The sheet stays open,
 * the field keeps what was typed, and nothing is stored — there is no icon to
 * remember and no icon to fall back from.
 *
 * The guarantee is about the text the field holds: a well-formed string. The
 * field is fed by a keyboard, which cannot produce an unpaired surrogate, and
 * a value that is not well-formed would not survive the JSON of the sync
 * document either. Nothing here validates that, and nothing here needs to: the
 * rule is "trim, then keep", not "accept only what a parser likes".
 */
fun customEmojiFromInput(raw: String): Emoji? =
    raw
        .trim()
        .takeIf(String::isNotEmpty)
        ?.let(::Emoji)
