package com.singularity.todo.core.text

/**
 * The number of Unicode code points in this string.
 *
 * [String.length] counts UTF-16 code units, so a character outside the Basic
 * Multilingual Plane — every emoji above U+FFFF, `🎉` among them — is charged
 * twice. Counting code points instead charges such a character once.
 *
 * This is a code-point count, not a count of visible characters and not a count
 * of grapheme clusters: `🇬🇧` is 2, `🧑‍💻` is 3, `👨‍👩‍👧‍👦` is 7. The helper stays
 * because it is a pure count with no provider behind it — the library's
 * emoji-aware count knows only its own catalog and needs an installed provider
 * (ADR `2026-11-13-the-emoji-helpers-have-no-library-equivalent.md`).
 *
 * Which quantity a name limit should bound — code points, catalog-recognized
 * emoji units or grapheme clusters — is open in #2933. Until that decision,
 * the behavior stays as it is, so names already accepted stay accepted.
 *
 * Use this wherever the limit is one a person reads as "characters". Do **not**
 * use it for a limit defined over an alphabet that is already ASCII: there the
 * two counts agree for every legal value, and switching would be churn that
 * fixes nothing.
 *
 * @see com.singularity.todo.core.auth.AuthDomain which is deliberately left on
 *   the raw length — a valid email address is ASCII per RFC 5321.
 */
fun String.visibleLength(): Int = codePointCount(0, length)
