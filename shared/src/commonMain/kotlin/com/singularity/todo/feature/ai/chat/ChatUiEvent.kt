package com.singularity.todo.feature.ai.chat

/**
 * One-shot events emitted by [ChatViewModel].
 */
sealed interface ChatUiEvent {
    data class Error(val message: String) : ChatUiEvent
}
