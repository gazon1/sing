package com.singularity.todo.feature.ai.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.feature.ai.TextGenPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) : ViewModel() {

    data class State(
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val isLoading: Boolean = false,
    )

    sealed interface Intent {
        data class InputChanged(val text: String) : Intent
        data object Send : Intent
    }

    private val _uiState = MutableStateFlow(State())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.InputChanged -> _uiState.update { it.copy(input = intent.text) }
            Intent.Send -> send()
        }
    }

    private fun send() = viewModelScope.launch {
        val current = _uiState.value
        val text = current.input.trim()
        if (text.isBlank() || current.isLoading) return@launch

        val assistantId = newId()
        _uiState.update {
            it.copy(
                input = "",
                isLoading = true,
                messages = it.messages +
                    ChatMessage(newId(), ChatRole.User, text) +
                    ChatMessage(assistantId, ChatRole.Assistant, ""),
            )
        }

        val collected = StringBuilder()
        runCatching {
            agent.streamChat(text).collect { chunk ->
                collected.append(chunk)
                _uiState.update { state ->
                    state.copy(messages = state.messages.replaceAssistantContent(assistantId, collected.toString()))
                }
            }
        }.onFailure { error ->
            log.e(error) { "AI stream failed [msg=${text.take(50)}]" }
            _events.emit(ChatUiEvent.Error(error.message ?: "AI request failed"))
        }

        _uiState.update { it.copy(isLoading = false) }
    }

    private fun List<ChatMessage>.replaceAssistantContent(id: String, content: String) =
        map { if (it.id == id) it.copy(content = content) else it }

    private fun newId(): String = idGen.next()
}

enum class ChatRole { User, Assistant }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
)
