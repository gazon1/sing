package com.singularity.todo.core.observability

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * Which consumer of the toolbox made the call.
 *
 * The toolbox has two consumers: external agents over MCP, and the agent inside the
 * application. Measuring only one of them gives a biased picture — the in-app agent
 * uses the same tools with a different mix, and a tool that only the in-app agent ever
 * calls looks unused when the numbers come from the server.
 */
enum class ToolCallSource { MCP, IN_APP }

/**
 * The closed set of outcomes.
 *
 * A closed set, because a report that has to parse free text to know whether a call
 * worked is a report nobody reads. A call either worked, failed in a way the caller
 * can act on, or failed in a way nobody can act on.
 */
enum class ToolCallOutcome { OK, BUSINESS_ERROR, INTERNAL_ERROR }

/**
 * One tool call, as a record.
 *
 * ## What it never contains
 *
 * Not the arguments, not the result, and not the text of an exception. An exception
 * message can carry anything — a path, an argument, a response body — and a record
 * that outlives the session is a copy of that data in a place nobody thought to
 * protect. What is left is the *shape* of the call: which tool, how it ended, how
 * long it took, and who made it.
 *
 * The error is a **code**, not a message. `validation` says what kind of failure it
 * was; the text of the failure is the server's business, not the record's.
 */
@Serializable
data class ToolCallEvent(
    /** Bumped when the shape of the record changes. The reader skips what it does not know. */
    val schemaVersion: Int = SCHEMA_VERSION,
    /** "tool_call" or "session_start". */
    val event: String = EVENT_TOOL_CALL,
    /** Wall-clock milliseconds since the epoch. */
    val ts: Long,
    val source: String,
    val tool: String? = null,
    val outcome: String? = null,
    val errorCode: String? = null,
    /** Measured with a monotonic clock: a wall clock can jump, and a negative duration is nonsense. */
    val durationMs: Long? = null,
    val sessionId: String? = null,
    /** "name@version" of the client, when there is one. Not an identity. */
    val client: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val EVENT_TOOL_CALL = "tool_call"
        const val EVENT_SESSION_START = "session_start"
    }
}

/**
 * How a failure is reported.
 *
 * [business] failures are the caller's to fix — a validation error, a missing
 * resource. [internal] failures are nobody's to fix but the author's.
 */
data class ToolCallError(val code: String, val business: Boolean)

/**
 * Where the records go.
 *
 * A port, so the recorder can be exercised with a fake and the file mechanics can
 * live in the platform that has them.
 */
interface ToolCallLog {
    /** Records one event. May throw; the caller decides what that means. */
    fun record(event: ToolCallEvent)
}

/** The transport context of a call: who was connected, and in which session. */
data class ToolCallContext(val sessionId: String? = null, val client: String? = null)

/**
 * Formats an event as one JSONL line.
 *
 * In common code on purpose: the *shape* of a record is the same everywhere, and only
 * the file mechanics differ between platforms.
 */
object ToolCallEventFormat {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    fun toLine(event: ToolCallEvent): String = json.encodeToString(ToolCallEvent.serializer(), event)
}

/**
 * Times a call, classifies its outcome, and records it.
 *
 * ## The guarantee
 *
 * Best-effort: **one record per completed attempt**, including the failed ones. It is
 * not a durable exactly-once log — a process that is killed mid-write leaves a
 * truncated line, and a write that fails leaves no line at all. The counter
 * [droppedRecords] says how many were lost, which is the honest way to report a
 * measurement that could not be taken.
 *
 * ## The rule
 *
 * A write failure never changes the outcome of the call being measured. An
 * observation that breaks the observed is worse than no observation: the tool would
 * fail for a reason that has nothing to do with the tool.
 */
class ToolCallRecorder(
    private val log: ToolCallLog,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val nowEpochMillis: () -> Long,
) {
    /** How many records could not be written. Reported, never logged through the broken writer. */
    var droppedRecords: Int = 0
        private set

    /**
     * Writes one record, and counts the ones it could not write.
     *
     * The catch is deliberately broad: a log that fails is a log of *anything*, and the
     * point of the counter is that no failure escapes into the call being measured. The
     * exception is logged rather than dropped, so a broken log is visible — through the
     * logger, not through the writer that just failed.
     */
    private companion object {
        const val LOG_TAG = "ToolCallRecorder"
    }

    @Suppress("TooGenericExceptionCaught")
    fun record(event: ToolCallEvent) {
        try {
            log.record(event)
        } catch (e: Throwable) {
            droppedRecords++
            Logger.withTag(LOG_TAG).w(e) { "Could not write a tool-call record" }
        }
    }

    /**
     * Runs [block], measures it, and records what happened.
     *
     * [classify] turns a failure into a code and says whether the caller can act on it.
     * It lives with the caller because only the caller knows what its own failures
     * mean: the MCP layer knows the difference between a validation error and a bug,
     * and the shared layer does not.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> timed(
        tool: String,
        source: ToolCallSource,
        context: ToolCallContext = ToolCallContext(),
        classify: (Throwable) -> ToolCallError? = { null },
        block: suspend () -> T,
    ): T {
        val mark = timeSource.markNow()
        var outcome = ToolCallOutcome.OK
        var errorCode: String? = null
        try {
            return block()
        } catch (e: CancellationException) {
            // A cancelled call is not a failed call. Counting it as one would report a
            // failure the caller never saw, and it must not be swallowed either.
            throw e
        } catch (e: Throwable) {
            val classified = classify(e)
            outcome = if (classified?.business == true) {
                ToolCallOutcome.BUSINESS_ERROR
            } else {
                ToolCallOutcome.INTERNAL_ERROR
            }
            errorCode = classified?.code ?: "internal"
            throw e
        } finally {
            record(
                ToolCallEvent(
                    ts = nowEpochMillis(),
                    source = source.name,
                    tool = tool,
                    outcome = outcome.name,
                    errorCode = errorCode,
                    durationMs = mark.elapsedNow().inWholeMilliseconds,
                    sessionId = context.sessionId,
                    client = context.client,
                ),
            )
        }
    }
}
