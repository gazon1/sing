package com.singularity.todo.feature.genui.core

// The wire format's two rules, in one place.
//
// The format is JSON Lines: a line that opens with a brace is a message the client applies, and
// everything else is prose the reader sees. Two components need that rule — the framer, which
// parses whole lines, and the answer's text, which decides what the reader may watch arrive —
// and a rule written twice is a rule that will be written twice differently.
//
// So it is written once, here, and both ask this file.

/**
 * Whether a line is a message.
 *
 * The line is expected to be trimmed already: the framer trims what it reads, and the answer's
 * text decides on the first character that is not whitespace.
 */
fun looksLikeMessage(line: String): Boolean = line.startsWith("{")

/**
 * Whether a line is a fence delimiter.
 *
 * A fence is a line of backticks, optionally carrying a language tag.
 *
 * A model asked for JSON Lines will often wrap the answer in one anyway, and the tag is the
 * part that makes it fiddly: ` ```json ` is a fence, ` ```json lines ` is not, and treating
 * the first as prose meant the response rendered nothing while looking perfectly correct.
 */
fun looksLikeFence(line: String): Boolean {
    if (!line.startsWith("```")) return false
    val rest: String = line.removePrefix("```").trim()
    return rest.isEmpty() || !rest.contains(' ')
}
