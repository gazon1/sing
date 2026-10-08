package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.genui.engine.GenuiOutcome
import com.singularity.todo.feature.genui.engine.GenuiSession
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.JsonElement
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import kotlinx.coroutines.launch

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
     * The generated-UI path, when the platform has a model to drive it.
     *
     * Null on a platform with no provider, and null in tests that are about the text conversation.
     * A reply may then carry text only — which is not a degraded mode, because most replies are
     * text and a surface is the exception rather than the rule.
     */
    private val genui: GenuiSession? = null,
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ChatViewModel.State, ChatViewModel.Intent, ChatUiEvent>(
        initialState = State(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    data class State(
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val isLoading: Boolean = false,
        /** Set when a surface could not be produced, so the screen can say so next to the reply. */
        val surfaceError: String? = null,
    )

    sealed interface Intent : MviIntent {
        data class InputChanged(val text: String) : Intent
        data object Send : Intent
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

    override fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.InputChanged -> updateState { it.copy(input = intent.text) }
            Intent.Send -> send(_state.value.input)
            Intent.DismissSurfaceError -> updateState { it.copy(surfaceError = null) }
            is Intent.SurfaceAction -> act(intent.name, intent.context)
        }
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
        updateState { current ->
            current.copy(
                input = "",
                isLoading = true,
                surfaceError = null,
                messages = current.messages +
                    (
                        if (showUserMessage) {
                            listOf(
                                ChatMessage(newId(), ChatRole.User, userText.trim()),
                            )
                        } else {
                            emptyList()
                        }
                    ) +
                    ChatMessage(assistantId, ChatRole.Assistant, ""),
            )
        }

        val session: GenuiSession? = genui
        if (session == null) {
            streamTextReply(assistantId, prompt)
            updateState { it.copy(isLoading = false) }
            return@launch
        }
        // A fresh identifier per answer: a surface belongs to the answer that drew it. Two answers
        // sharing one identifier would make an older message redraw whatever the newest answer
        // produced, and an answer with no screen would empty the one an older message points at.
        renderSurfaceReply(assistantId, prompt, session, SurfaceId(newId()))
        updateState { it.copy(isLoading = false) }
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
     * Asks the model for a surface, and falls back to the text conversation if it will not produce
     * one.
     *
     * The fallback is not a courtesy to the model. A surface is an answer to "show me", and a user
     * who asked a question deserves a sentence even when the model found it easier to emit JSON.
     */
    private suspend fun renderSurfaceReply(
        assistantId: String,
        prompt: String,
        session: GenuiSession,
        surfaceId: SurfaceId,
    ) {
        val outcome: GenuiOutcome = runCatchingCancellable { session.respond(prompt, surfaceId) }
            .getOrElse { error: Throwable ->
                log.e(error) { "GenUI session failed" }
                crashReporter.report(error, GENUI_FAILED)
                emit(ChatUiEvent.Error("Could not generate a screen: ${error.message ?: "unknown error"}"))
                streamTextReply(assistantId, prompt)
                return
            }

        val text: String = outcome.text.ifBlank { "Here is what I found." }
        applyReply(assistantId, text, outcome.surfaceId)
        if (outcome.failed) {
            updateState {
                it.copy(surfaceError = "The screen could not be completed: ${outcome.feedback()}")
            }
        }
    }

    private suspend fun streamTextReply(assistantId: String, prompt: String) {
        val collected = StringBuilder()
        runCatchingCancellable {
            agent.streamChat(prompt).collect { chunk ->
                collected.append(chunk)
                applyReply(assistantId, collected.toString(), null)
            }
        }.onFailure { error ->
            log.e(error) { "AI stream failed" }
            crashReporter.report(error, CHAT_STREAM_FAILED)
            emit(ChatUiEvent.Error(error.message ?: "AI request failed"))
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
                },
            )
        }
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val CHAT_STREAM_FAILED = "chat.stream_failed"
        const val GENUI_FAILED = "chat.genui_failed"
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
