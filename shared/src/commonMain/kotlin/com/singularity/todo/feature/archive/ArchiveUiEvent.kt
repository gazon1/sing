package com.singularity.todo.feature.archive

/**
 * One-shot events emitted by [ArchiveViewModel].
 */
sealed interface ArchiveUiEvent {
    data class Archived(val message: String) : ArchiveUiEvent
    data class Error(val message: String) : ArchiveUiEvent
}
