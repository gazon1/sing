package com.singularity.todo.feature.genui.core

/**
 * One complete piece of a model's output, recovered from a stream of fragments.
 *
 * The distinction between [Message] and [Prose] is what keeps a normal conversation working. A
 * reply that opens with "Here are your tasks:" and then emits the surface is not a malformed
 * response — the sentence belongs to the user, and the lines after it belong to the surface.
 */
sealed interface FramedLine {

    /** A line that looks like a message; it may still be rejected by the parser. */
    data class Message(val text: String) : FramedLine

    /** A line of ordinary text, emitted around or instead of messages. */
    data class Prose(val text: String) : FramedLine

    /** A line that was skipped, with the reason. */
    data class Dropped(val reason: String) : FramedLine
}

/**
 * Assembles a stream of model output fragments into complete lines.
 *
 * This class exists because the two ends of the pipeline disagreed about what a string is. The
 * transport emits token deltas — a fragment that ends wherever the token boundary falls, routinely
 * in the middle of a JSON string — and the parser takes one complete object per call. Handing
 * deltas straight to the parser produced no events at all for any reply that took more than one
 * chunk, and the comment claiming otherwise was simply wrong. The engine was never called, so
 * nothing ever surfaced the contradiction.
 *
 * A model also wraps its output in a markdown fence, and splits lines on either newline
 * convention; both are handled here rather than in the parser, so the parser can assume it is
 * looking at one whole line.
 *
 * Stateless between calls except for the buffer, so one instance serves one response.
 */
class LineFramer(private val maxLineLength: Int = MAX_LINE_LENGTH) {

    private val buffer = StringBuilder()
    private var insideFence: Boolean = false
    private var skippingOversizedLine: Boolean = false

    /** Feeds [chunk] and returns every complete line it finished. */
    fun accept(chunk: String): List<FramedLine> {
        val produced: MutableList<FramedLine> = mutableListOf()
        for (character in chunk) {
            when {
                skippingOversizedLine -> if (character == '\n') finishSkipped(produced)
                character == '\n' -> endOfLine(produced)
                else -> appendToBuffer(character)
            }
        }
        return produced
    }

    /**
     * Ends the response and returns whatever is left.
     *
     * A trailing fragment that looks like a message was cut off by the model, so it is reported as
     * dropped rather than parsed — passing a truncated object to the parser would produce a
     * malformed-line error attributed to the client rather than to the truncation.
     */
    fun finish(): List<FramedLine> {
        val produced: MutableList<FramedLine> = mutableListOf()
        if (skippingOversizedLine) {
            finishSkipped(produced)
        }
        val remainder: String = buffer.toString().trim()
        buffer.clear()
        insideFence = false
        if (remainder.isEmpty()) return produced
        produced += if (remainder.startsWith("{")) {
            FramedLine.Dropped("Response ended mid-message; ${remainder.length} characters discarded")
        } else {
            FramedLine.Prose(remainder)
        }
        return produced
    }

    private fun appendToBuffer(character: Char) {
        if (buffer.length >= maxLineLength) {
            buffer.clear()
            skippingOversizedLine = true
            return
        }
        buffer.append(character)
    }

    private fun endOfLine(produced: MutableList<FramedLine>) {
        if (skippingOversizedLine) {
            finishSkipped(produced)
            return
        }
        val line: String = buffer.toString().trim()
        buffer.clear()
        if (line.isEmpty()) return
        if (isFenceDelimiter(line)) {
            insideFence = !insideFence
            return
        }
        produced += classify(line)
    }

    private fun finishSkipped(produced: MutableList<FramedLine>) {
        skippingOversizedLine = false
        produced += FramedLine.Dropped("Line exceeded $maxLineLength characters and was skipped")
    }

    private fun classify(line: String): FramedLine = when {
        insideFence -> if (line.startsWith("{")) FramedLine.Message(line) else FramedLine.Prose(line)
        line.startsWith("{") -> FramedLine.Message(line)
        else -> FramedLine.Prose(line)
    }

    /**
     * A fence is a line of backticks, optionally carrying a language tag.
     *
     * A model asked for JSON Lines will often wrap the answer in one anyway, and the tag is the
     * part that makes it fiddly: ` ```json ` is a fence, ` ```json lines ` is not, and treating
     * the first as prose meant the response rendered nothing while looking perfectly correct.
     */
    private fun isFenceDelimiter(line: String): Boolean {
        if (!line.startsWith("```")) return false
        val rest: String = line.removePrefix("```").trim()
        return rest.isEmpty() || !rest.contains(' ')
    }

    companion object {
        /**
         * Upper bound on one message.
         *
         * A model that has lost the plot emits an ever-growing line, and without a bound the buffer
         * grows until the process runs out of memory. 256 KB is roughly two orders of magnitude
         * above a real surface, so the bound is only ever hit by output that was not going to parse.
         */
        const val MAX_LINE_LENGTH: Int = 256 * 1024
    }
}
