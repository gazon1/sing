package com.singularity.todo.core.text

/**
 * Number of visible characters in this string.
 *
 * [String.length] counts UTF-16 code units, so a character outside the Basic
 * Multilingual Plane — every emoji above U+FFFF, `🎉` among them — is charged
 * twice. A name the user reads as 25 characters measures 50 and is rejected by a
 * 50-character limit that its own error message says it satisfies.
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
