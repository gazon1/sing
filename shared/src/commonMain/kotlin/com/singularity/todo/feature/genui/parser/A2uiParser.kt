package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiError
import com.singularity.todo.feature.genui.core.A2uiErrorCode
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiSeverity
import com.singularity.todo.feature.genui.core.FramedLine
import com.singularity.todo.feature.genui.core.GenuiUsageCounter
import com.singularity.todo.feature.genui.core.LineFramer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Parses A2UI JSON-Lines into [A2uiParseOutcome].
 *
 * Pure: no state, no dependencies beyond the catalog the operations are checked against.
 *
 * Every input is classified rather than discarded. The previous signature returned `UiEvent?`, and
 * a blank line, a malformed line, an unknown operation, an unknown component kind and a missing
 * required property all produced the same `null` — five different problems the caller could not
 * count, could not report, and could not tell a model about.
 *
 * This class decides *what a line is*. Turning the body of a recognised message into an event is
 * [A2uiMessageDecoder]'s job, because the two answer different questions: one is about the
 * envelope and the other about the contract, and a line can fail either without the other being
 * consulted.
 */
class A2uiParser(
    private val catalog: A2uiCatalog = SingularityCatalog,
    private val json: Json = defaultJson,
    /**
     * Where the tally of which kinds a model actually draws accumulates.
     *
     * Injected rather than created here so that one process-wide count can span every parser
     * rather than resetting whenever a new session is built — the question is what the catalog earns
     * across a process, not what one conversation happened to use.
     */
    usage: GenuiUsageCounter = GenuiUsageCounter(),
) {

    private val messages: A2uiMessageDecoder = A2uiMessageDecoder(catalog, usage)

    /** The running tally of which component kinds a model actually draws. */
    fun usageCounter(): GenuiUsageCounter = messages.usageCounter()

    /** Classifies one complete line. */
    fun parseLine(line: String): A2uiParseOutcome =
        if (line.isBlank()) {
            A2uiParseOutcome.Skipped(A2uiErrorCode.MALFORMED_LINE, "blank line")
        } else {
            classify(line)
        }

    /**
     * Reads the line as an envelope and dispatches it.
     *
     * Prose never reaches here: the framer routes it to [FramedLine.Prose], and a line that starts
     * with `{` was a message the model meant to send. If it cannot be read, the contract was broken
     * and the model has to be told — reported as a skip it would be swallowed by the correction
     * loop, and an answer made only of unreadable JSON would be accepted as a success with nothing
     * on screen.
     */
    private fun classify(line: String): A2uiParseOutcome =
        when (val element: JsonElement? = runCatching { json.parseToJsonElement(line) }.getOrNull()) {
            is JsonObject -> parseEnvelope(element)
            null -> unreadable("not valid JSON")
            else -> unreadable("not a JSON object")
        }

    /**
     * Which of the four operations this is, and then what it says.
     *
     * The operation is looked up among the known keys rather than taken from whichever key happens
     * to come first. Taking the first key meant any object whose leading field was not a message
     * body — a version field, a comment, a stray key — was forced through the decoder and refused,
     * which reads as "unknown operation" and is a completely different problem.
     */
    private fun parseEnvelope(envelope: JsonObject): A2uiParseOutcome {
        val declared: Int? = envelope["schemaVersion"]?.let { (it as? JsonPrimitive)?.intOrNull }
        if (declared != null && declared > A2UI_CURRENT_SCHEMA_VERSION) {
            return A2uiParseOutcome.Failed(unsupportedVersion(declared))
        }
        val operation: String = OPERATIONS.firstOrNull { it in envelope }
            ?: return A2uiParseOutcome.Failed(unknownOperation())
        val body: JsonObject = envelope[operation] as? JsonObject
            ?: return A2uiParseOutcome.Failed(operationIsNotAnObject(operation))
        return messages.decode(operation, body)
    }

    /**
     * Classifies a stream of complete lines.
     *
     * For callers that already have lines — the release-note payload is whole lines, not tokens.
     */
    fun parseStream(lines: Flow<String>): Flow<A2uiParseOutcome> = flow {
        lines.collect { line -> emit(parseLine(line)) }
    }

    /**
     * Reassembles a stream of model output fragments into lines, then classifies each one.
     *
     * Prose is not an error and is not emitted as a failure: a reply that opens with a sentence and
     * then gives a surface is a reply with both parts, and reporting the sentence to the model as a
     * contract violation would be the worst possible answer to a user who asked a question.
     */
    fun parseTokens(tokens: Flow<String>): Flow<A2uiParseOutcome> = flow {
        val framer: LineFramer = LineFramer()
        tokens.collect { chunk -> framer.accept(chunk).forEach { emit(it.asOutcome()) } }
        framer.finish().forEach { emit(it.asOutcome()) }
    }

    /**
     * A framed line that has not been read yet, as an outcome of its own.
     *
     * Prose and an already-dropped line are both recorded as skips rather than failures: the framer
     * has said what it is, and re-reporting it would put a sentence the user is reading into the
     * correction prompt.
     */
    private fun FramedLine.asOutcome(): A2uiParseOutcome = when (this) {
        is FramedLine.Message -> parseLine(text)
        is FramedLine.Prose -> A2uiParseOutcome.Skipped(A2uiErrorCode.MALFORMED_LINE, "prose")
        is FramedLine.Dropped -> A2uiParseOutcome.Skipped(A2uiErrorCode.LINE_TOO_LONG, reason)
    }

    // ─── Envelope rejections ───────────────────────────────────────────────

    private fun unreadable(detail: String): A2uiParseOutcome.Failed = A2uiParseOutcome.Failed(
        A2uiError(
            code = A2uiErrorCode.MALFORMED_LINE,
            message = "The line looks like a message but is $detail.",
            pointer = "/",
            severity = A2uiSeverity.MESSAGE,
        ),
    )

    private fun unsupportedVersion(declared: Int): A2uiError = A2uiError(
        code = A2uiErrorCode.UNSUPPORTED_SCHEMA_VERSION,
        message = "This client implements schema version $A2UI_CURRENT_SCHEMA_VERSION; " +
            "the message declares $declared.",
        pointer = "/schemaVersion",
    )

    /**
     * The legal operations travel with the rejection.
     *
     * A model that guessed an operation name cannot infer the right one, and a message saying only
     * "no known operation" is the one rejection text it has least to act on.
     */
    private fun unknownOperation(): A2uiError = A2uiError(
        code = A2uiErrorCode.UNKNOWN_OPERATION,
        message = "No known operation in this message.",
        pointer = "/",
        allowed = OPERATIONS.sorted(),
    )

    private fun operationIsNotAnObject(operation: String): A2uiError = A2uiError(
        code = A2uiErrorCode.MALFORMED_LINE,
        message = "'$operation' is not a JSON object.",
        pointer = "/$operation",
    )

    companion object {
        /** Current A2UI schema version. Events with schemaVersion > this are rejected. */
        const val A2UI_CURRENT_SCHEMA_VERSION = 1

        /** The four operations this client implements. */
        val OPERATIONS: Set<String> = setOf("createSurface", "updateComponents", "updateData", "deleteSurface")

        @OptIn(ExperimentalSerializationApi::class)
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
