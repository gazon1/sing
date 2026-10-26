package com.singularity.todo.feature.ai.chat

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.observability.ToolUsageEvent
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlin.time.Clock
import kotlin.time.measureTimedValue

/**
 * A [TextGenPort] decorator that records every AI call to [RoomUsageRecorder].
 *
 * Lives in `feature/ai/chat/` because it must depend on `ProfileAwareCurrentUser`
 * (a `feature` component), which is allowed: `feature → core`. It cannot live in
 * `core.observability` because that would make `core` depend on `feature`.
 *
 * ## Approximation for token counts
 * Exact token counts are not available from [TextGenPort]. This decorator uses
 * `message.length / 4` as a rough approximation (typical compression ratio for
 * English text). For accurate billing, [TextGenPort] would need instrumentation
 * hooks — see ADR `2026-10-02-usage-recording-textgen-architecture.md`.
 *
 * ## Usage
 * Wire in `AiDiModule`:
 * ```
 * single<TextGenPort> { UsageRecordingTextGen(get(), get(), get(), get()) }
 * ```
 * where the delegate is the underlying Koog-backed implementation.
 */
class UsageRecordingTextGen(
    private val delegate: TextGenPort,
    private val usageRecorder: RoomUsageRecorder,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : TextGenPort {

    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> {
        val (result, duration) = measureTimedValue {
            delegate.generate(prompt, systemPrompt, model)
        }
        val response = result.getOrNull() ?: ""
        val error = result.exceptionOrNull()?.message

        usageRecorder.record(
            ToolUsageEvent(
                toolName = "chat_generate",
                modelId = model ?: "unknown",
                inputTokens = approximateTokens(prompt),
                outputTokens = approximateTokens(response),
                totalTokens = approximateTokens(prompt) + approximateTokens(response),
                costUsdMicros = null, // model pricing not available without instrumentation hooks
                durationMs = duration.inWholeMilliseconds,
                profileId = currentUser.scopedUserId.value.value,
                error = error,
                timestamp = clock.now(),
            ),
        )
        return result
    }

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> {
        val start = clock.now()
        val outputBuilder = StringBuilder()

        return delegate.streamChat(message, instructions)
            // One writer, one row per call. `onCompletion` runs on every ending of the
            // flow — normal completion, failure and cancellation alike — and the cause it
            // is handed is the failure when there was one. Recording again in `catch` gave
            // the failure path two rows for the same call, which is the one thing the
            // ledger must not hold: a correction is a further call and a real expense, but
            // a second row for the same call is a cost that never happened.
            .onCompletion { cause ->
                val durationMs = (clock.now() - start).inWholeMilliseconds
                val output = outputBuilder.toString()
                val error = cause?.message

                usageRecorder.record(
                    ToolUsageEvent(
                        toolName = "chat_stream",
                        modelId = "chat", // model not exposed by streamChat signature
                        inputTokens = approximateTokens(message),
                        outputTokens = approximateTokens(output),
                        totalTokens = approximateTokens(message) + approximateTokens(output),
                        costUsdMicros = null,
                        durationMs = durationMs,
                        profileId = currentUser.scopedUserId.value.value,
                        error = error,
                        timestamp = start,
                    ),
                )
            }
            .map { event ->
                // Only the text counts towards the response the model produced. The activity
                // around it is not output, and counting a tool call as tokens would make the
                // recorded usage depend on how chatty the tool names are.
                if (event is AgentEvent.TextDelta) outputBuilder.append(event.text)
                event
            }
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        delegate.listModels(baseUrl, apiKey)

    /**
     * Rough token approximation: characters / 4.
     * This overestimates for non-English text but is a reasonable first approximation.
     * Replace with real token counts when [TextGenPort] exposes instrumentation hooks.
     */
    private fun approximateTokens(text: String): Int = text.length / 4
}
