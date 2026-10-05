package com.singularity.todo.feature.genui.engine

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.CatalogPrompt
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiError
import com.singularity.todo.feature.genui.core.A2uiErrorCode
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiSeverity
import com.singularity.todo.feature.genui.core.FramedLine
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.core.LineFramer
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.feature.genui.transport.GenuiTransport

private val logger = Logger.withTag("GenuiSession")

/** What one turn of a conversation was, kept so the next turn can refer to it. */
private data class GenuiTurn(val userPrompt: String, val replyText: String, val hadSurface: Boolean)

/**
 * One exchange with the model: send, read, apply, and — if anything was rejected — say so and try
 * again.
 *
 * The session owns a conversation, not a screen. It remembers the turns it has had so a follow-up
 * question and a tap inside a rendered surface can be answered in context; that memory is why an
 * action in a surface can be the next turn rather than a separate subsystem.
 *
 * @param maxCorrectionTurns how many extra calls may be made after a rejection. Two is a
 *   compromise: one is often not enough for a model that has misunderstood the format, and three
 *   starts costing more than the surface is worth. A constant rather than a setting, because a
 *   user should not be able to spend tokens by turning a knob — and should not see the knob.
 */
class GenuiSession(
    private val transport: GenuiTransport,
    private val parser: A2uiParser,
    private val processor: A2uiMessageProcessor,
    private val controller: SurfaceController,
    private val catalog: A2uiCatalog = SingularityCatalog,
    private val maxCorrectionTurns: Int = DEFAULT_MAX_CORRECTION_TURNS,
    private val rejectionCounter: GenuiRejectionCounter = GenuiRejectionCounter(),
) {

    /**
     * Reports which components this answer drew.
     *
     * Logged per turn rather than on demand because the interesting moment is while the
     * answer is still being looked at: "it never uses the card component" is a conclusion
     * about the catalog that somebody has to reach, and it is much easier to reach while
     * looking at the surface that failed to use it.
     */
    private fun reportUsage() {
        parser.usageCounter().report()
    }

    private val history: MutableList<GenuiTurn> = mutableListOf()

    /**
     * Sends [prompt] and applies everything the model returns, correcting it up to
     * [maxCorrectionTurns] times.
     *
     * @param surfaceId the identifier this answer's surface is filed under. Required, and chosen by
     *   the caller, because a surface belongs to an answer: two answers sharing one identifier
     *   would mean an older message drawing whatever the newest answer rendered, and an answer
     *   with no surface erasing the one an older message still points at.
     */
    suspend fun respond(
        prompt: String,
        surfaceId: SurfaceId,
        systemPrompt: String = "",
    ): GenuiOutcome {
        var attempts: Int = 0
        val collected: MutableList<A2uiError> = mutableListOf()
        // Only the immediately preceding turn's rejections go back to the model. The accumulated
        // list is for the user; feeding it back would tell the model to fix things it already
        // fixed, which is how a correction loop starts undoing correct work.
        var lastErrors: List<A2uiError> = emptyList()

        while (true) {
            val turn: Turn = runTurn(prompt, surfaceId, systemPrompt, lastErrors, attempts)
            attempts += 1
            collected += turn.errors
            lastErrors = turn.errors

            val rejections: List<A2uiError> = turn.errors.filterNot { it.severity == A2uiSeverity.ADVISORY }
            if (rejections.isEmpty()) {
                remember(prompt, turn)
                rejectionCounter.recordAndReport(collected)
                reportUsage()
                return GenuiOutcome(turn.text, turn.drawnAs(surfaceId), attempts, collected, failed = false)
            }
            if (attempts > maxCorrectionTurns) {
                logger.w { "GenUI surface not produced after $attempts attempts; ${rejections.size} rejections" }
                remember(prompt, turn)
                rejectionCounter.recordAndReport(collected)
                return GenuiOutcome(turn.text, turn.drawnAs(surfaceId), attempts, collected, failed = true)
            }
        }
    }

    /** One model call: stream it, frame it, apply it, and collect what went wrong. */
    private suspend fun runTurn(
        prompt: String,
        surfaceId: SurfaceId,
        systemPrompt: String,
        previousErrors: List<A2uiError>,
        attempt: Int,
    ): Turn {
        // A fresh identifier each turn: the surface of a previous answer stays exactly as it was
        // rendered, which is the point of keeping it in the conversation at all.
        controller.apply(UiEvent.DeleteSurface(surfaceId))
        // One framer per turn: it holds the partial line between chunks, and a shared one would
        // carry a half-read message from the previous turn into this one.
        val framer = LineFramer()
        val prose: StringBuilder = StringBuilder()
        val errors: MutableList<A2uiError> = mutableListOf()
        var applied: Boolean = false

        val request: String = buildRequest(prompt, previousErrors, attempt)
        transport.send(request, systemPromptFor(systemPrompt)).collect { chunk: String ->
            framer.accept(chunk).forEach { line: FramedLine ->
                applied = absorb(line, prose, errors, surfaceId, applied) || applied
            }
        }
        framer.finish().forEach { line: FramedLine ->
            applied = absorb(line, prose, errors, surfaceId, applied) || applied
        }
        return Turn(prose.toString().trim(), errors.toList(), applied)
    }

    /**
     * Takes one framed line, and reports whether it changed anything on screen.
     *
     * Prose and messages are told apart here rather than in the parser because they mean different
     * things to a user: the prose is the reply, a message is the surface. A sentence around the
     * surface is not a malformed response, and reporting it to the model as one would be the worst
     * possible answer to a question that was answered.
     */
    private fun absorb(
        line: FramedLine,
        prose: StringBuilder,
        errors: MutableList<A2uiError>,
        surfaceId: SurfaceId,
        appliedSoFar: Boolean,
    ): Boolean = when (line) {
        is FramedLine.Prose -> {
            if (prose.isNotEmpty()) prose.append(' ')
            prose.append(line.text)
            appliedSoFar
        }

        is FramedLine.Dropped -> {
            // A line the transport could not deliver is not a contract violation, and repeating
            // the same request with the same token limit truncates it the same way. It only counts
            // as a rejection when nothing arrived at all — otherwise the answer would be reported
            // as a success with an empty screen behind it.
            errors += A2uiError(
                code = A2uiErrorCode.MALFORMED_LINE,
                message = line.reason,
                surfaceId = surfaceId,
                severity = if (appliedSoFar) A2uiSeverity.ADVISORY else A2uiSeverity.MESSAGE,
            )
            appliedSoFar
        }

        is FramedLine.Message -> when (val outcome: A2uiParseOutcome = parser.parseLine(line.text)) {
            is A2uiParseOutcome.Parsed -> {
                // The identifier in the message groups the lines of one answer; the caller's
                // identifier says which answer this is. See UiEvent.retargeted.
                val result = processor.apply(outcome.event.retargeted(surfaceId), outcome.errors)
                errors += result.errors
                result.applied
            }

            is A2uiParseOutcome.Failed -> {
                errors += outcome.error
                false
            }

            is A2uiParseOutcome.Skipped -> appliedSoFar
        }
    }

    /**
     * The request for this attempt.
     *
     * It repeats the original prompt, because the transport carries one message and not a
     * conversation: a follow-up that said only "fix it" would leave the model with no idea what it
     * was trying to build. A correction then names each rejection, which is the one piece of
     * information the model cannot reconstruct on its own — in particular the list of components
     * that do exist, when the failure was that it used one that does not.
     */
    private fun buildRequest(prompt: String, previousErrors: List<A2uiError>, attempt: Int): String =
        buildString {
            append(historyBlock())
            append(prompt)
            if (attempt == 0) return@buildString
            append("\n\nThat surface was rejected. Send the corrected surface only, and fix these:")
            previousErrors
                .filterNot { it.severity == A2uiSeverity.ADVISORY }
                .distinctBy { it.code to it.componentId }
                .forEach { error ->
                    append("\n- ")
                    append(error.asFeedback())
                }
        }

    /**
     * The turns so far, in a form a model can use.
     *
     * Only the user's words and whether an answer had a screen: replaying whole surfaces would put
     * a JSON document into the conversation that the model might copy from, which is exactly how a
     * surface starts repeating the last one instead of being built for the new question.
     */
    private fun historyBlock(): String {
        if (history.isEmpty()) return ""
        val lines: List<String> = history.takeLast(MAX_HISTORY).map { turn: GenuiTurn ->
            val answer: String = when {
                turn.replyText.isBlank() -> "(showed a screen)"
                else -> turn.replyText.take(REPLY_EXCERPT)
            }
            "user: ${turn.userPrompt.take(REPLY_EXCERPT)}\nassistant: $answer"
        }
        return buildString {
            append("Conversation so far:\n")
            lines.forEach { line ->
                append("- ")
                append(line)
                append('\n')
            }
            append('\n')
            append("Now answer this:\n")
        }
    }

    private fun remember(prompt: String, turn: Turn) {
        history += GenuiTurn(prompt, turn.text, turn.applied)
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    private fun systemPromptFor(callerPrompt: String): String {
        val generated: String = CatalogPrompt.render(catalog)
        return if (callerPrompt.isBlank()) generated else "$callerPrompt\n\n$generated"
    }

    private data class Turn(val text: String, val errors: List<A2uiError>, val applied: Boolean) {
        /**
         * The identifier this answer's surface can be addressed by, or null when it drew none.
         *
         * Null matters more than it looks: a chat message points at a surface by identifier, and an
         * answer of plain prose must not leave a message pointing at nothing — the renderer would
         * mount an empty frame, and the reply would look broken next to an answer that worked.
         */
        fun drawnAs(surfaceId: SurfaceId): SurfaceId? = if (applied) surfaceId else null
    }

    companion object {
        const val DEFAULT_MAX_CORRECTION_TURNS: Int = 2

        /** Turns kept for context. Enough for "and now only overdue", not enough to grow forever. */
        const val MAX_HISTORY: Int = 6

        private const val REPLY_EXCERPT: Int = 200
    }
}
