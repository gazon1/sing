package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.llm.AgentActivityState
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.genui.engine.SurfaceTurn
import com.singularity.todo.feature.genui.engine.SurfaceTurnFactory
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonElement
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * AI chat state and intents.
 *
 * The screen subscribes to [state] (continuous) and [events] (one-shot).
 * Composable stays thin — every action is expressed as an [Intent] and the
 * ViewModel is the single source of truth for messages, input, and loading.
 */
class ChatViewModel(
    private val log: Logger,
    private val agent: TextGenPort,
    private val idGen: IdGenerator,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    /**
     * The screens an answer may draw, when the platform has a model to drive them.
     *
     * Null on a platform with no provider, and null in tests that are about the text conversation.
     * An answer may then carry text only — which is not a degraded mode, because most answers are
     * text and a screen is the exception rather than the rule.
     */
    private val surfaces: SurfaceTurnFactory? = null,
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ChatViewModel.State, ChatViewModel.Intent, ChatUiEvent>(
        initialState = State(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    data class State(
        val messages: ImmutableList<ChatMessage> = persistentListOf(),
        /**
         * The text of the chat input.
         *
         * A [FieldOwnedText], so that every write to it is named: the field's own text, a seed
         * or a clear.
         */
        val input: FieldOwnedText = FieldOwnedText.empty(),
        val isLoading: Boolean = false,
        /** Set when a surface could not be produced, so the screen can say so next to the reply. */
        val surfaceError: String? = null,
        /**
         * What the agent has done in the current turn.
         *
         * Folded from the turn's events by one shared rule, so that this screen and the
         * external-agent screen agree on what a tool call update *does* to what is on
         * screen — a call that is running and the same call finished are one card, not two.
         */
        val activity: AgentActivityState = AgentActivityState(),
        /**
         * How the last turn ended, or null while one is running.
         *
         * The outcome is set exactly once per turn, and it is the answer to "is this thing
         * still going?" — a turn that ended without an outcome would leave the reader
         * watching a spinner that no longer has anything behind it.
         */
        val turnOutcome: AgentTurnOutcome? = null,
    ) {
        /**
         * The input as the field's source.
         *
         * The ViewModel clears the input when the message is sent, so the value is replaced only
         * with one the field never produced: the field's echo guard needs no lifting, and there is
         * no counter to read.
         *
         * The promise is in the type of the value, not in this sentence: the input is a
         * [FieldOwnedText], and the only ways to write one are the field's own text, a seed and a
         * clear.
         */
        val inputSource: StaticText get() = StaticText(input.text)
    }

    sealed interface Intent : MviIntent {
        data class InputChanged(val text: String) : Intent
        data object Send : Intent

        /**
         * The reader stops the turn that is running.
         *
         * An agent that cannot be stopped is an agent the reader has to wait for, and waiting is
         * the one thing a reader who has understood the situation should not have to do.
         */
        data object Stop : Intent

        data object DismissSurfaceError : Intent

        /**
         * The user pressed a control inside a rendered surface.
         *
         * A surface is part of the conversation, not a picture of one, so a press is the next turn
         * of that conversation rather than a call out to a tool: the model is already in context
         * and the user is already in a flow. [context] carries what the control was bound to.
         */
        data class SurfaceAction(val name: String, val context: JsonElement?) : Intent
    }

    /**
     * The turn that is running, if one is running.
     *
     * Held so that the reader can stop it. The turn is a coroutine of the ViewModel's scope, and
     * cancelling it is the only honest way to stop it: the cancellation travels through the model
     * call and reaches the provider, so the turn really ends rather than being abandoned while
     * the model keeps talking to a socket nobody reads.
     */
    private var turnJob: Job? = null

    override fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.InputChanged -> updateState { it.copy(input = it.input.echoed(intent.text)) }
            Intent.Send -> send(_state.value.input.text)
            Intent.Stop -> stop()
            Intent.DismissSurfaceError -> updateState { it.copy(surfaceError = null) }
            is Intent.SurfaceAction -> act(intent.name, intent.context)
        }
    }

    /** Stops the turn that is running, if one is running. */
    private fun stop() {
        turnJob?.cancel()
    }

    /** One turn of the conversation, started by what the user typed. */
    private fun send(userText: String) {
        // Empty is not a short question. Without this the input box's trailing newline and stray
        // spaces would buy the user a model call and an answer to nothing.
        if (userText.isBlank()) return
        turn(userText.trim(), prompt = userText.trim(), showUserMessage = true)
    }

    /**
     * One turn started by a press inside a rendered surface.
     *
     * No user message is added: the user did not type anything, and showing them a bubble quoting
     * an action identifier back at them would put a protocol detail where a person is looking. The
     * press still reaches the model, phrased as an event in the conversation.
     */
    private fun act(name: String, context: JsonElement?) = turn(
        userText = name,
        prompt = actionPrompt(name, context),
        showUserMessage = false,
    )

    /**
     * The shared body of both kinds of turn.
     *
     * @param showUserMessage false for a press, so the conversation keeps showing only what people
     *   actually said.
     */
    private fun turn(userText: String, prompt: String, showUserMessage: Boolean) = vmScope.launch {
        if (_state.value.isLoading) return@launch

        val assistantId = newId()
        // A fresh identifier per answer: a screen belongs to the answer that drew it. Two
        // answers sharing one identifier would make an older message redraw whatever the
        // newest answer produced, and an answer with no screen would empty the one an older
        // message points at.
        val surfaceId = SurfaceId(newId())
        // The conversation as it was before this question, so the turn is asked in the context
        // of the questions that came before it and not in the context of itself.
        val history: List<ChatTurnRecord> = historyRecords()

        updateState { current ->
            current.copy(
                input = current.input.cleared(),
                isLoading = true,
                surfaceError = null,
                activity = AgentActivityState(),
                turnOutcome = null,
                messages = (
                    current.messages +
                        (
                            if (showUserMessage) {
                                listOf(ChatMessage(newId(), ChatRole.User, userText.trim()))
                            } else {
                                emptyList()
                            }
                        ) +
                        ChatMessage(assistantId, ChatRole.Assistant, "")
                ).toImmutableList(),
            )
        }

        turnJob = coroutineContext[Job]
        try {
            runTurn(assistantId, prompt, surfaceId, history)
        } finally {
            // The loading state is cleared on every path, including a cancellation: a turn
            // that was cancelled while the spinner was up must not leave the spinner up.
            updateState { it.copy(isLoading = false) }
            turnJob = null
        }
    }

    /**
     * The conversation so far, in the form the next turn hears it.
     *
     * A user's question and the answer it produced are one record: an answer that arrived without
     * a question belongs to no question. An answer that drew only a screen carries no words, and
     * [ChatTurn] names it as "showed a screen" rather than replaying the screen itself.
     */
    private fun historyRecords(): List<ChatTurnRecord> {
        val records: MutableList<ChatTurnRecord> = mutableListOf()
        var question: String? = null
        _state.value.messages.forEach { message: ChatMessage ->
            when (message.role) {
                ChatRole.User -> {
                    question?.let { records += ChatTurnRecord(it, "") }
                    question = message.content
                }

                ChatRole.Assistant -> {
                    question?.let { asked: String ->
                        records += ChatTurnRecord(asked, message.content)
                    }
                    question = null
                }
            }
        }
        question?.let { records += ChatTurnRecord(it, "") }
        return records
    }

    /**
     * How a press is written to the model.
     *
     * As an event in the conversation rather than as raw JSON, so the model reads it the way it
     * reads everything else. The bound data comes along because a press without it is a press the
     * model cannot act on: which task was pressed matters as much as what was pressed.
     */
    private fun actionPrompt(name: String, context: JsonElement?): String = buildString {
        append("The user pressed \"$name\" on the screen you showed.")
        if (context != null) {
            append(" It was bound to: ")
            append(context)
            append('.')
        }
        append(" Continue the conversation.")
    }

    /**
     * Asks the model, once, and folds what comes back into the screen.
     *
     * There is one path here, and it is the path production takes. The earlier shape had two —
     * one for a platform with a screen and one without — and the one production took was the one
     * no test drove, so the cards and the outcome rules were verified against a branch the
     * application never ran.
     */
    @Suppress("NoSwallowedCancellation", "TooGenericExceptionCaught")
    private suspend fun runTurn(
        assistantId: String,
        prompt: String,
        surfaceId: SurfaceId,
        history: List<ChatTurnRecord>,
    ) {
        val surface: SurfaceTurn? = surfaces?.begin(surfaceId)
        val turn = ChatTurn(agent = agent, surface = surface, history = history)
        val collected = StringBuilder()
        var completed = false
        try {
            turn.run(prompt).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        collected.append(event.text)
                        applyReply(assistantId, collected.toString().trim(), null)
                    }

                    is AgentEvent.ToolCallUpdated -> applyToolCall(event)

                    is AgentEvent.PlanUpdated -> updateState { state ->
                        state.copy(activity = state.activity + event)
                    }

                    AgentEvent.TurnCompleted -> completed = true
                }
            }
        } catch (cancellation: CancellationException) {
            // A cancelled turn is cancelled, not failed. The partial answer stays on screen.
            setOutcome(AgentTurnOutcome.Cancelled)
            throw cancellation
        } catch (error: Throwable) {
            log.e(error) { "AI stream failed" }
            crashReporter.report(error, CHAT_STREAM_FAILED)
            emit(ChatUiEvent.Error(error.message ?: "AI request failed"))
            setOutcome(AgentTurnOutcome.Failed(error.message ?: "AI request failed"))
            return
        }

        // The screen, if the answer drew one. The identifier is filed on the answer only now:
        // an answer that drew nothing must not point at a screen, and an answer that drew one
        // must not point at nothing.
        if (surface?.drawn == true) {
            applyReply(assistantId, collected.toString(), surfaceId)
        }
        val rejections = surface?.rejections.orEmpty()
        if (rejections.isNotEmpty()) {
            val feedback: String = surface?.readerFeedback().orEmpty()
            updateState {
                it.copy(surfaceError = "The screen could not be completed: $feedback")
            }
        }

        // The stream ended without the completion event: the agent did not finish. Reporting
        // that as a completed turn would tell the reader the answer is complete when it is
        // not — and the missing text would be the very thing they needed to know.
        if (!completed) {
            setOutcome(AgentTurnOutcome.Failed("The response ended before it was complete."))
        } else {
            setOutcome(AgentTurnOutcome.Completed())
        }
    }

    /**
     * Records a tool call, or updates the card of one that is already known.
     *
     * The same call arrives twice — once when it starts, once when it finishes — and the two
     * are one card. The rule that says so lives in the activity's `plus`, because the
     * external-agent screen will fold its events the same way.
     */
    private fun applyToolCall(event: AgentEvent.ToolCallUpdated) {
        updateState { state ->
            state.copy(activity = state.activity + event)
        }
    }

    /**
     * Sets the turn's outcome, once.
     *
     * The guard is the guarantee: a late event arriving after the turn ended cannot change
     * how it ended, and a second failure cannot overwrite the first.
     */
    private fun setOutcome(outcome: AgentTurnOutcome) {
        updateState { state ->
            if (state.turnOutcome != null) state else state.copy(turnOutcome = outcome)
        }
    }

    private fun applyReply(assistantId: String, text: String, surfaceId: SurfaceId?) {
        updateState { state ->
            state.copy(
                messages = state.messages.map { message: ChatMessage ->
                    if (message.id != assistantId) {
                        message
                    } else {
                        message.copy(content = text, surfaceId = surfaceId)
                    }
                }.toImmutableList(),
            )
        }
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val CHAT_STREAM_FAILED = "chat.stream_failed"
    }

    private fun newId(): String = idGen.next()
}

enum class ChatRole { User, Assistant }

/**
 * One message in the conversation.
 *
 * @param surfaceId the surface drawn alongside this message, when the model produced one. Null for
 *   the overwhelming majority of messages, which is why it is a field here rather than a separate
 *   concept: a reply that is text and a screen at once is still one reply.
 */
data class ChatMessage(val id: String, val role: ChatRole, val content: String, val surfaceId: SurfaceId? = null)

/**
 * One-shot events emitted by [ChatViewModel].
 */
sealed interface ChatUiEvent : MviEvent {
    data class Error(val message: String) : ChatUiEvent
}
