package com.singularity.todo.feature.archive

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [ArchiveViewModel].
 */
sealed interface ArchiveUiEvent : MviEvent {
    data class Archived(val message: String) : ArchiveUiEvent

    /** The sweep ran and moved nothing: another device took the same work, or it was gone. */
    data object NothingToArchive : ArchiveUiEvent

    data class Error(val message: String) : ArchiveUiEvent
}
