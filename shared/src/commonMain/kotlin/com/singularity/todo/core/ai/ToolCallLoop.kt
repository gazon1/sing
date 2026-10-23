package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.ToolCallStatus
import com.singularity.todo.core.llm.toolCallSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One chat turn in which the model may call tools.
 *
 * ## Why this exists
 *
 * The chat screen used to stream text and nothing else: the model was asked a question and
 * answered from whatever it could infer. The tools existed, and the *other* entry point
 * (`generate`, which runs a full Koog [ai.koog.agents.core.agent.AIAgent]) could call them —
 * but the chat screen, the one a person actually talks to, could not. A question about the
 * user's own tasks was answered from the model's imagination.
 *
 * Koog's own agent runtime is not available here: it is written against the JVM's reflection
 * and its own event pipeline, and this project runs the same code on Android and on the
 * desktop. So the loop is written out — stream a round, run whatever the model asked for,
 * feed the outcome back, stream another round — and it is small enough to read in one
 * sitting.
 *
 * ## The shape of a round
 *
 * A round is one pass of the model over the conversation so far. The model may answer in
 * prose, or ask for tools. When it asks for tools, the requests are executed, their outcomes
 * are appended to the conversation as *tool results*, and another round runs.
 *
 * The conversation grows in the shape the model expects:
 *
 * - the assistant's own turn — its text and its tool calls — as one assistant message;
 * - the outcomes of those calls, as one user message holding one result per call.
 *
 * That is the shape Koog's own agent produces, and the shape the OpenAI-compatible
 * backends this project talks to expect.
 *
 * ## The cap
 *
 * A model that keeps asking for tools must not be able to hold the chat open forever, so
 * the number of tool-calling rounds is bounded. When the bound is reached, the calls of the
 * last round are still executed — a request the model made is honoured, not dropped — and
 * the model then gets **one** more round in which no tool is offered, so it has to answer
 * in words instead of asking again.
 *
 * ## What is emitted
 *
 * The model's text as it arrives, **and** what it did while producing it.
 *
 * This used to emit the text and nothing else, on the principle that tool calls and tool
 * outcomes are the conversation's business, not the reader's: the person asked a question
 * and wanted the answer. That principle was right when the chat answered questions. It
 * stopped being right the moment a question could be about the user's own data, because
 * then three questions had no answer on the screen — *what did you just do to my tasks?*,
 * *that tool you called, did it work?*, and *you have been busy for two minutes, are you
 * stuck?* A failed tool was invisible, and a cancelled turn left the spinner running.
 *
 * So the loop now emits [AgentEvent]s: the text as it arrives, a tool call before it runs
 * and again after it finished, and the end of the turn. The reader sees the work; the model
 * still sees exactly what it saw before.
 *
 * A failed tool is an **event**, not an exception. The loop already turned a tool failure
 * into a result the model could read — throwing would end the conversation over one failed
 * tool — and that same outcome now also becomes a visible `FAILED` status. The failure is
 * reported twice, to two different audiences: to the model as a result it can act on, and
 * to the reader as a card that says it failed.
 */
internal class ToolCallLoop(
    /**
     * The tools the model may call.
     *
     * The list is already filtered — the kill switch has been applied, and the recording
     * wrapper has been put on — so this class does not decide what is reachable.
     */
    private val tools: List<Tool<*, *>>,
    /**
     * How many rounds may contain tool calls before the model is told to answer in words.
     */
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    json: Json = Json { ignoreUnknownKeys = true },
) {

    private val toolsByName: Map<String, Tool<*, *>> = tools.associateBy { it.descriptor.name }

    private val descriptors: List<ToolDescriptor> = tools.map { it.descriptor }

    private val serializer: JSONSerializer = KotlinxSerializer(json)

    /**
     * Runs the turn, emitting the model's text as it arrives.
     *
     * @param initialPrompt the conversation so far — the system prompt and the user's message.
     * @param stream how to ask the model for the next round. It receives the conversation and
     *   the tools it may call, and returns the frames the model produced. The model itself
     *   is the caller's business, and the caller closes over it.
     */
    fun run(
        initialPrompt: Prompt,
        stream: (Prompt, List<ToolDescriptor>) -> Flow<StreamFrame>,
    ): Flow<AgentEvent> = flow {
        var current = initialPrompt
        var toolRounds = 0

        while (true) {
            val outcome = streamRound(current, descriptors, "r$toolRounds", stream) { emit(AgentEvent.TextDelta(it)) }

            if (outcome.calls.isEmpty()) {
                emit(AgentEvent.TurnCompleted)
                return@flow
            }

            val results = outcome.calls.map { pending ->
                val call = pending.call
                // The call is announced before it runs, so a card appears while the tool is
                // still working rather than after it has already finished.
                emit(AgentEvent.ToolCallUpdated(pending.id, call.tool, ToolCallStatus.RUNNING))
                val result = execute(call)
                emit(
                    AgentEvent.ToolCallUpdated(
                        id = pending.id,
                        name = call.tool,
                        status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                        summary = toolCallSummary(call.tool, call.argsJson.toString()),
                    ),
                )
                result
            }
            current = conversationWith(current, outcome, results)
            toolRounds++

            if (toolRounds >= maxRounds) {
                // The cap is reached. The calls of this round were honoured above; the model
                // now gets one round with no tools, so it answers in words rather than asking
                // again.
                streamRound(current, emptyList(), "r${toolRounds + 1}", stream) { emit(AgentEvent.TextDelta(it)) }
                emit(AgentEvent.TurnCompleted)
                return@flow
            }
        }
    }

    /**
     * One pass of the model over the conversation.
     *
     * The text is passed on as it arrives, and also collected, because the round's text has
     * to be replayed to the model in the assistant message that follows it — a round the
     * model spent saying "let me look" and then asked a tool must be remembered as both.
     *
     * @param callPrefix distinguishes the identifiers of calls made in different rounds, so
     *   two rounds that both call a tool do not produce the same identifier.
     * @param onText receives each piece of text the model produced.
     */
    private suspend fun streamRound(
        current: Prompt,
        descriptors: List<ToolDescriptor>,
        callPrefix: String,
        stream: (Prompt, List<ToolDescriptor>) -> Flow<StreamFrame>,
        onText: suspend (String) -> Unit,
    ): RoundOutcome {
        val text = StringBuilder()
        val calls = mutableListOf<PendingCall>()
        var callSeq = 0

        stream(current, descriptors).collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> {
                    text.append(frame.text)
                    onText(frame.text)
                }

                is StreamFrame.ToolCallComplete -> {
                    // A call without an identifier cannot be answered: the outcome of a call
                    // is matched to the call by identifier, and a generated one would not
                    // match what the model was told it asked for. So the identifier is
                    // settled here, once, and used on both sides.
                    val id = frame.id ?: "${callPrefix}_call_$callSeq"
                    callSeq++
                    calls += PendingCall(
                        id = id,
                        call = MessagePart.Tool.Call(id = id, tool = frame.name, args = frame.content),
                    )
                }

                else -> Unit
            }
        }

        return RoundOutcome(text = text.toString(), calls = calls)
    }

    /**
     * The conversation after a round that asked for tools.
     *
     * The assistant's turn keeps both its text and its calls, and the outcomes arrive as
     * one user message — one result per call, in the order the calls were made.
     */
    private fun conversationWith(
        current: Prompt,
        outcome: RoundOutcome,
        results: List<MessagePart.Tool.Result>,
    ): Prompt = prompt(current, KoogClock.System) {
        assistant(
            buildList {
                if (outcome.text.isNotBlank()) add(MessagePart.Text(outcome.text))
                addAll(outcome.calls.map { it.call })
            },
        )
        user { results.forEach { toolResult(it) } }
    }

    /**
     * Runs one call and turns its outcome into something the model can read.
     *
     * A failure is a result, not a crash: the model asked, and the honest answer is that the
     * call did not succeed. Throwing would end the conversation over one failed tool.
     */
    // A tool may throw anything, and a failed tool is an answer the model can read rather
    // than the end of the conversation. Cancellation is re-thrown above the general catch.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(call: MessagePart.Tool.Call): MessagePart.Tool.Result {
        val tool = toolsByName[call.tool]
            ?: return failure(call, "There is no tool named \"${call.tool}\".")

        return try {
            @Suppress("UNCHECKED_CAST")
            val typed = tool as Tool<Any, Any>
            val args = typed.decodeArgs(call.argsJson.toKoogJSONObject(), serializer)
            val result = typed.execute(args)
            MessagePart.Tool.Result(call.id, call.tool, typed.encodeResultToString(result, serializer))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (malformed: SerializationException) {
            failure(call, malformedArguments(call, malformed))
        } catch (malformed: IllegalArgumentException) {
            failure(call, malformedArguments(call, malformed))
        } catch (failure: Throwable) {
            failure(call, "The tool failed: ${failure.message ?: failure::class.simpleName}")
        }
    }

    /**
     * What to say when the caller's arguments did not fit the tool.
     *
     * The model is the one that can fix this, so the message says what to look at: the
     * names and the allowed values are in the tool's own description.
     */
    private fun malformedArguments(call: MessagePart.Tool.Call, malformed: Throwable): String =
        "The arguments did not fit the tool \"${call.tool}\" (${malformed.message}). " +
            "Check the argument names and the allowed values in the tool's description."

    private fun failure(call: MessagePart.Tool.Call, message: String): MessagePart.Tool.Result =
        MessagePart.Tool.Result(call.id, call.tool, message, isError = true)

    /** What one round of the model produced. */
    private data class RoundOutcome(val text: String, val calls: List<PendingCall>)

    /**
     * A call the model asked for, with the identifier that names it everywhere.
     *
     * The identifier is settled once, when the call is made, and carried alongside the call
     * rather than read back out of it: the call's own identifier is nullable, and a card
     * needs a name that is stable from the moment the tool starts to the moment it finishes.
     */
    private data class PendingCall(val id: String, val call: MessagePart.Tool.Call)

    private companion object {
        /**
         * How many rounds may contain tool calls.
         *
         * Enough for a question that needs two lookups ("which of my overdue tasks is in
         * the project I am about to archive?"), few enough that a model stuck in a loop
         * cannot hold the chat open.
         */
        const val DEFAULT_MAX_ROUNDS = 4
    }
}
