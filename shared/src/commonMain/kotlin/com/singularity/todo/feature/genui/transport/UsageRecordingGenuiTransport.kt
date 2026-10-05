package com.singularity.todo.feature.genui.transport

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.observability.ToolUsageEvent
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

private val logger = Logger.withTag("UsageGenuiTransport")

/**
 * Records every model call made to produce a surface, and passes the stream through untouched.
 *
 * Generated surfaces are model calls, and a feature that spends tokens without writing them down is
 * a feature whose cost nobody can see. Every other AI call in the app already records itself — see
 * `UsageRecordingTextGen` — and this decorator is the same arrangement for the GenUI path, which is
 * a separate transport and would otherwise be the one hole in the record.
 *
 * Lives here rather than in `core.observability` because it needs [ProfileAwareCurrentUser], a
 * feature type, and usage rows are keyed by profile.
 *
 * Token counts are approximated as `length / 4`, the same approximation
 * [com.singularity.todo.feature.ai.chat.UsageRecordingTextGen] documents: the streaming transport
 * reports characters, not tokens, and a number that is consistently wrong is better than a column
 * that is empty.
 */
class UsageRecordingGenuiTransport(
    private val delegate: GenuiTransport,
    private val usageRecorder: RoomUsageRecorder,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : GenuiTransport {

    @Suppress("TooGenericExceptionCaught") // a provider may throw anything; see the catch below
    override suspend fun send(prompt: String, systemPrompt: String): Flow<String> = flow {
        val model: String = MODEL_LABEL
        val promptTokens: Int = estimate((prompt + systemPrompt).length)
        val started: kotlin.time.Instant = clock.now()
        val response: StringBuilder = StringBuilder()
        var failure: String? = null

        try {
            delegate.send(prompt, systemPrompt).collect { chunk: String ->
                response.append(chunk)
                emit(chunk)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a failure to report: the caller walked away, and writing a usage
            // row for a call the user abandoned would make the ledger disagree with the app.
            throw cancellation
        } catch (thrown: Throwable) {
            // The point is the breadth: a provider SDK can throw anything at all, and a surface is
            // worth less than a crash. Whatever already streamed through has been emitted, so the
            // caller gets a partial answer and a recorded failure instead of nothing.
            logger.e(thrown) { "GenUI transport failed" }
            failure = thrown.message ?: thrown::class.simpleName
        }

        val durationMs: Long = (clock.now() - started).inWholeMilliseconds
        val outputTokens: Int = estimate(response.length)
        record(
            ToolUsageEvent(
                toolName = TOOL_NAME,
                modelId = model,
                inputTokens = promptTokens,
                outputTokens = outputTokens,
                totalTokens = promptTokens + outputTokens,
                costUsdMicros = null,
                durationMs = durationMs,
                profileId = currentUser.liveScopedUserId.first().value,
                error = failure,
                timestamp = started,
            ),
        )
    }

    /**
     * Records one call, without ever failing the surface it describes.
     *
     * `runCatchingCancellable` rather than `runCatching`: a plain one swallows
     * `CancellationException`, so a cancelled turn would still write a usage row and keep the scope
     * it was supposed to release. That is the ledger disagreeing with the app.
     */
    private suspend fun record(event: ToolUsageEvent) {
        runCatchingCancellable { usageRecorder.record(event) }
            .onFailure { logger.e(it) { "GenUI usage record failed" } }
    }

    private fun estimate(characters: Int): Int = characters / CHARS_PER_TOKEN

    private companion object {
        const val TOOL_NAME = "genui.surface"
        const val MODEL_LABEL = "koog"
        const val CHARS_PER_TOKEN = 4
    }
}
