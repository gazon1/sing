package com.singularity.todo.feature.genui.engine

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

/**
 * One answer's screen: the part of a model's answer that is drawn rather than printed.
 *
 * ## What this is
 *
 * The screen's mechanics, without a model call. The framer recovers whole lines from the
 * stream, the parser reads a message out of a line, the processor applies it to the surfaces,
 * and the controller holds what is on screen. All of that used to live in a session that also
 * asked a model of its own; this is the same mechanics with the asking left out, because the
 * turn asks the model now.
 *
 * ## What belongs to an answer
 *
 * The identifier is the caller's, and it is per answer: a screen belongs to the answer that
 * drew it, and two answers sharing one identifier would mean an older answer drawing whatever
 * the newest one produced.
 *
 * ## The corrections
 *
 * A screen that was rejected is asked for again, and what the model is told about it is
 * [correctionRequest]. The rules that decide what counts as a rejection come with it: an advisory
 * error is reported but does not cost the screen, and a message the parser rejected whole is a
 * rejection while one that was skipped is not.
 */
class SurfaceTurn(
    /** The identifier this answer's screen is filed under. */
    val id: SurfaceId,
    private val parser: A2uiParser,
    private val processor: A2uiMessageProcessor,
    private val controller: SurfaceController,
    private val catalog: A2uiCatalog = SingularityCatalog,
    private val rejectionCounter: GenuiRejectionCounter = GenuiRejectionCounter(),
) {

    /**
     * What the model is told it may draw.
     *
     * It belongs to the screen and not to the model call: the same model asked a question that
     * needs no screen is told nothing about screens, and the catalogue is only worth its tokens
     * to a call that might use it.
     */
    val instructions: String = CatalogPrompt.render(catalog)

    /** One framer per attempt: it holds the partial line between chunks. */
    private var framer: LineFramer = LineFramer()

    private val errors: MutableList<A2uiError> = mutableListOf()
    private var applied: Boolean = false

    /**
     * Starts an attempt.
     *
     * The screen of the previous attempt goes first, so a correction replaces what it corrects
     * rather than drawing over it, and the framer is new: a shared one would carry a half-read
     * message from the previous attempt into this one.
     */
    fun beginAttempt() {
        controller.apply(UiEvent.DeleteSurface(id))
        framer = LineFramer()
        errors.clear()
        applied = false
    }

    /** Takes one piece of the answer. */
    fun accept(chunk: String) {
        framer.accept(chunk).forEach { line: FramedLine -> absorb(line) }
    }

    /** Ends the answer, and applies whatever the last line held. */
    fun finish() {
        framer.finish().forEach { line: FramedLine -> absorb(line) }
    }

    /** Whether this answer drew anything. */
    val drawn: Boolean get() = applied

    /** Everything that was wrong, for the log and for the reader. */
    val collected: List<A2uiError> get() = errors.toList()

    /**
     * The errors that cost the screen its place.
     *
     * An advisory error is reported to the model and to the reader, but it does not cost the
     * screen: one dropped component leaves the rest of it standing.
     */
    val rejections: List<A2uiError> get() = errors.filterNot { it.severity == A2uiSeverity.ADVISORY }

    /**
     * What the model is told when its screen was rejected.
     *
     * The question is named again, because the correction is about that question and not about
     * the screen in the abstract, and the rejections are named one by one, because a correction
     * that says "something was wrong" is a correction the model cannot act on.
     */
    fun correctionRequest(): String = buildString {
        append("\n\nThat surface was rejected. Send the corrected surface only, and fix these:")
        rejections
            .distinctBy { it.code to it.componentId }
            .forEach { error: A2uiError ->
                append("\n- ")
                append(error.asFeedback())
            }
    }

    /**
     * What the reader is told about a screen that could not be completed.
     *
     * The same reasons, in the same words, without the instruction to the model: the reader is
     * not being asked to fix anything.
     */
    fun readerFeedback(): String = collected
        .distinctBy { it.code to it.componentId }
        .joinToString("\n") { error: A2uiError -> error.asFeedback() }

    /**
     * Reports what this answer drew, and what was rejected.
     *
     * Two instruments, both of which used to belong to the session that asked the model: the
     * tally of rejections, and the report of which components the answer actually used. The
     * question they answer — which mistake does the model actually make — is the question that
     * decides whether the answer is a better prompt, a looser catalog or a different component
     * set, and it cannot be answered from an impression.
     */
    fun report() {
        rejectionCounter.recordAndReport(errors)
        parser.usageCounter().report()
    }

    /**
     * Takes one framed line, and reports what it changed.
     *
     * The prose is not this class's business. The reader's text is filtered by the answer's own
     * filter ([com.singularity.todo.feature.genui.engine.AnswerText]) in the turn that owns the
     * answer; a screen only has to notice the prose and leave it where it is. So the line is
     * accepted and dropped. A sentence around the screen is not a malformed response, and
     * reporting it to the model as one would be the worst possible answer to a question that was
     * answered.
     */
    private fun absorb(line: FramedLine) {
        when (line) {
            is FramedLine.Prose -> Unit

            is FramedLine.Dropped -> {
                // A line the transport could not deliver is not a contract violation, and repeating
                // the same request with the same token limit truncates it the same way. It only counts
                // as a rejection when nothing arrived at all — otherwise the answer would be reported
                // as a success with an empty screen behind it.
                errors += A2uiError(
                    code = A2uiErrorCode.MALFORMED_LINE,
                    message = line.reason,
                    surfaceId = id,
                    severity = if (applied) A2uiSeverity.ADVISORY else A2uiSeverity.MESSAGE,
                )
            }

            is FramedLine.Message -> when (val outcome: A2uiParseOutcome = parser.parseLine(line.text)) {
                is A2uiParseOutcome.Parsed -> {
                    // The identifier in the message groups the lines of one answer; the caller's
                    // identifier says which answer this is. See UiEvent.retargeted.
                    val result = processor.apply(outcome.event.retargeted(id), outcome.errors)
                    errors += result.errors
                    if (result.applied) applied = true
                }

                is A2uiParseOutcome.Failed -> errors += outcome.error

                is A2uiParseOutcome.Skipped -> Unit
            }
        }
    }
}
