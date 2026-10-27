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
 * How deep a JSON message is, so that a message spread over several lines is still one message.
 *
 * ## Why the depth is counted and not the lines
 *
 * The format is JSON Lines — one message per line — and a model asked for it often
 * pretty-prints instead. A pretty-printed message is still one message: its first line
 * opens it and a later line closes it.
 *
 * Reading it line by line would do two wrong things at once. The framer would offer the
 * parser a truncated object to reject — a rejection the model is then told about, for a
 * message the model had in fact written correctly. And the answer's text would show the
 * reader the message's own body as prose, because a line that starts with a quote is not a
 * line that starts with a brace.
 *
 * So the depth is counted, and the count is shared: the framer and the answer's text ask
 * this file, and cannot disagree about where a message ends.
 *
 * ## Why the strings are counted apart
 *
 * A brace inside a string is a character, not a structure: `"title": "a } in the title"`
 * opens and closes nothing. So the counter walks the text the way a JSON reader does — in
 * a string, a quote ends the string and a backslash escapes the next character; out of
 * one, a quote opens it.
 */
class JsonMessageDepth {

    private var depth: Int = 0
    private var inString: Boolean = false
    private var escaped: Boolean = false

    /** The depth the message is at. Zero means the message is closed. */
    val current: Int get() = depth

    /**
     * Takes a piece of the message and returns the depth it leaves behind.
     *
     * The text may be a whole line or a single character: the answer's text reads the
     * answer as it arrives, one character at a time.
     */
    fun accept(text: String): Int {
        for (character in text) accept(character)
        return depth
    }

    /**
     * [accept] for one character.
     *
     * A stream is fed one character at a time, and going through [String] for each
     * of them allocated a string per character. The character form is the one a
     * streaming caller should use.
     */
    fun accept(character: Char): Int {
        when {
            escaped -> escaped = false
            inString && character == '\\' -> escaped = true
            inString && character == '"' -> inString = false
            !inString && character == '"' -> inString = true
            !inString && character == '{' -> depth++
            !inString && character == '}' -> depth--
        }
        return depth
    }
}

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
