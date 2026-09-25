package com.singularity.todo.feature.archive

import com.singularity.todo.core.ui.mvi.MviEvent

/**
 * One-shot events emitted by [ArchiveViewModel].
 */
sealed interface ArchiveUiEvent : MviEvent {
    data class Archived(val message: String) : ArchiveUiEvent
    data class Error(val message: String) : ArchiveUiEvent
}
