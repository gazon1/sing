package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.ai.TextGenPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI chat state and intents.
 *
 * The screen subscribes to [uiState] (continuous) and [events] (one-shot).
 * Composable stays thin — every action is expressed as an [Intent] and the
 * ViewModel is the single source of truth for messages, input, and loading.
 */
class ChatViewModel(
    private val log: Logger,
    private val agent: TextGenPort,
    private val idGen: IdGenerator,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ChatViewModel.State, ChatViewModel.Intent, ChatUiEvent>(
        initialState = State(),
        scope = scope,
    ) {

    data class State(
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val isLoading: Boolean = false,
    )

    sealed interface Intent : MviIntent {
        data class InputChanged(val text: String) : Intent
        data object Send : Intent
    }

    /** Public UI state — StateFlow for screen observation. */
    private val _uiState = MutableStateFlow(State())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    override fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.InputChanged -> _uiState.value = _uiState.value.copy(input = intent.text)
            Intent.Send -> send()
        }
    }

    private fun send() = vmScope.launch {
        val current = _uiState.value
        val text = current.input.trim()
        if (text.isBlank() || current.isLoading) return@launch

        val assistantId = newId()
        _uiState.value = _uiState.value.copy(
            input = "",
            isLoading = true,
            messages = _uiState.value.messages +
                ChatMessage(newId(), ChatRole.User, text) +
                ChatMessage(assistantId, ChatRole.Assistant, ""),
        )

        val collected = StringBuilder()
        runCatching {
            agent.streamChat(text).collect { chunk ->
                collected.append(chunk)
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.replaceAssistantContent(assistantId, collected.toString()),
                )
            }
        }.onFailure { error ->
            log.e(error) { "AI stream failed [msg=${text.take(50)}]" }
            emit(ChatUiEvent.Error(error.message ?: "AI request failed"))
        }

        _uiState.value = _uiState.value.copy(isLoading = false)
    }

    private fun List<ChatMessage>.replaceAssistantContent(id: String, content: String) =
        map { if (it.id == id) it.copy(content = content) else it }

    private fun newId(): String = idGen.next()
}

enum class ChatRole { User, Assistant }

data class ChatMessage(val id: String, val role: ChatRole, val content: String)

/**
 * One-shot events emitted by [ChatViewModel].
 */
sealed interface ChatUiEvent : MviEvent {
    data class Error(val message: String) : ChatUiEvent
}
