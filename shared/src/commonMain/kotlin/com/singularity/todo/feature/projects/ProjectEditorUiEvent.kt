package com.singularity.todo.feature.projects

/**
 * One-shot events emitted by [ProjectEditorViewModel].
 */
sealed interface ProjectEditorUiEvent {
    data object NavigateBack : ProjectEditorUiEvent
}
