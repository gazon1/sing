package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.projects.domain.model.ProjectId

/**
 * UI state for the project editor screen.
 * When [projectId] is null, the screen operates in create mode.
 * When [projectId] is non-null, it operates in edit mode (loads existing project).
 */
data class ProjectEditorUiState(
    val projectId: ProjectId? = null,
    val name: String = "",
    val description: String = "",
    val color: Int = DEFAULT_COLOR,
    val icon: String? = null,
    val parentId: ProjectId? = null,
    val saving: Boolean = false,
    val loading: Boolean = false,
    val errorMessage: String? = null,
) {
    companion object {
        const val DEFAULT_COLOR = 0xFF1976D2.toInt() // blue
    }

    val isEditMode: Boolean get() = projectId != null
}

sealed interface ProjectEditorIntent : MviIntent {
    data class NameChanged(val name: String) : ProjectEditorIntent
    data class ColorChanged(val color: Int) : ProjectEditorIntent
    data class IconChanged(val icon: String?) : ProjectEditorIntent
    data class DescriptionChanged(val description: String) : ProjectEditorIntent
    data class ParentChanged(val parentId: ProjectId?) : ProjectEditorIntent
    data object Save : ProjectEditorIntent
    data object ErrorShown : ProjectEditorIntent
}
