package com.singularity.todo.feature.genui.engine

import com.singularity.todo.feature.genui.core.JsonMessageDepth
import com.singularity.todo.feature.genui.core.looksLikeFence

/**
 * The reader's text: everything the model wrote that is not a screen.
 *
 * ## Why the answer has to be filtered at all
 *
 * The wire format is JSON Lines: a sentence, then a screen, in one stream of text. The reader
 * must see the sentence and must not see the screen — a screen is rendered, not printed — and
 * the two arrive interleaved, in the same characters.
 *
 * The screen is the part that starts with a brace, and the rule that says so is the format's
 * own ([com.singularity.todo.feature.genui.core.looksLikeMessage]), shared with the framer
 * that parses the screen. So this filter and the framer cannot disagree about what a screen
 * is.
 *
 * ## Why the prose is not held back until the line ends
 *
 * The framer decides about a line when the line ends, because a message has to be whole before
 * it can be parsed. The reader does not have to wait: a line that has already shown a
 * character that is not a brace cannot become a message, so it is shown as it arrives.
 *
 * The one line that is held back is a line that opens with backticks. A model asked for JSON
 * Lines often wraps the answer in a fence anyway, and a fence is not prose — but whether a
 * line of backticks is a fence or a sentence is only known when the line ends. So that line
 * waits; every other line does not.
 *
 * ## What it is not
 *
 * It is not a parser and not a validator. A line it hides may still be rejected by the screen's
 * own rules; this filter only decides what the reader may watch arrive.
 */
class AnswerText {

    /** What is known about the line being read. */
    private enum class Line {
        /** Nothing decisive yet: only whitespace so far. */
        UNDECIDED,

        /** The line is prose, and it is being shown as it arrives. */
        PROSE,

        /** The line is a message, and none of it is shown. */
        MESSAGE,

        /** The line opens with backticks: it may be a fence, and it waits for its end. */
        HELD,
    }

    private var line: Line = Line.UNDECIDED

    /** The whitespace or the held line, until the line is decided. */
    private val held = StringBuilder()

    /**
     * How deep the message being hidden is.
     *
     * A message may span several lines: a model asked for JSON Lines often pretty-prints
     * instead, and a pretty-printed message is still one message. So the reader's text
     * hides the message until it closes — otherwise the message's own body would arrive as
     * prose, because a line that starts with a quote does not start with a brace.
     *
     * The count is the format's own ([com.singularity.todo.feature.genui.core.JsonMessageDepth]),
     * shared with the framer that parses the message, so the two cannot disagree about
     * where a message ends.
     */
    private val depth = JsonMessageDepth()

    /**
     * Takes a piece of the model's answer and returns the part the reader may see.
     *
     * The text that is withheld is withheld for good: a message is not shown later, and neither
     * is a fence.
     */
    fun accept(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val shown = StringBuilder()
        for (character in chunk) {
            if (character == '\n') {
                endOfLine(shown)
            } else {
                take(character, shown)
            }
        }
        return shown.toString()
    }

    /**
     * Takes one character that is not a line break.
     *
     * The line's own state decides what happens to it, and each state does one thing: prose
     * is shown, a message is swallowed, and a line that is not decided yet waits for the
     * first character that decides it.
     */
    private fun take(character: Char, shown: StringBuilder) {
        when (line) {
            Line.UNDECIDED -> decide(character, shown)
            Line.PROSE -> shown.append(character)
            Line.MESSAGE -> insideMessage(character)
            Line.HELD -> held.append(character)
        }
    }

    /**
     * Swallows a character of the message, and notices when the message closes.
     *
     * A message that closes mid-line is followed by whatever came after it, and that is
     * prose: the reader may see it.
     */
    private fun insideMessage(character: Char) {
        if (depth.accept(character.toString()) <= 0) {
            line = Line.UNDECIDED
        }
    }

    /**
     * Decides what an undecided line is, from its first character that is not whitespace.
     *
     * The whitespace before it is held rather than shown, because the decision is about the
     * line's first *character*, and a line that starts with spaces is still a message.
     */
    private fun decide(character: Char, shown: StringBuilder) {
        if (character.isWhitespace()) {
            held.append(character)
            return
        }
        line = when {
            character == '{' -> Line.MESSAGE
            character == '`' -> Line.HELD
            else -> Line.PROSE
        }
        held.clear()
        if (line == Line.PROSE) {
            shown.append(character)
        } else {
            held.append(character)
            if (line == Line.MESSAGE) {
                depth.accept(character.toString())
            }
        }
    }

    /**
     * Ends the answer, and returns whatever the last line still owed the reader.
     *
     * A model that ends its answer in the middle of a line leaves that line undecided, and the
     * framer has the same question: a fragment that opens with a brace was cut off, and one
     * that does not is prose.
     */
    fun finish(): String {
        val shown = StringBuilder()
        when (line) {
            Line.HELD -> {
                val text = held.toString().trim()
                if (!looksLikeFence(text)) shown.append(text)
            }

            Line.UNDECIDED, Line.MESSAGE -> Unit

            Line.PROSE -> Unit
        }
        line = Line.UNDECIDED
        held.clear()
        return shown.toString()
    }

    /** Ends the line: a held line is decided now, and the next line starts undecided. */
    private fun endOfLine(shown: StringBuilder) {
        when (line) {
            Line.HELD -> {
                val text = held.toString().trim()
                if (!looksLikeFence(text)) shown.append(text)
            }

            Line.PROSE -> shown.append('\n')

            Line.MESSAGE -> {
                // The message continues on the next line: it is not closed yet, and the reader
                // waits for the rest of it.
                if (depth.current <= 0) {
                    line = Line.UNDECIDED
                    held.clear()
                }
                return
            }

            Line.UNDECIDED -> Unit
        }
        line = Line.UNDECIDED
        held.clear()
    }
}
