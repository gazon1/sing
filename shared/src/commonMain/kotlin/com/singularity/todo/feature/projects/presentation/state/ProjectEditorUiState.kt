package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.feature.projects.domain.model.ProjectId

/**
 * UI state for the project editor screen.
 * When [projectId] is null, the screen operates in create mode.
 * When [projectId] is non-null, it operates in edit mode (loads existing project).
 *
 * The name and the description are [FieldOwnedText]s, so that every write to them is named:
 * the field's own text, a seed, or a clear.
 */
data class ProjectEditorUiState(
    val projectId: ProjectId? = null,
    val name: FieldOwnedText = FieldOwnedText.empty(),
    val description: FieldOwnedText = FieldOwnedText.empty(),
    val color: Int = DEFAULT_COLOR,
    val icon: String? = null,
    val parentId: ProjectId? = null,
    val saving: Boolean = false,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    // Baseline captured when the project was loaded (edit mode) or at creation
    // (create mode). [isDirty] compares the live fields against this baseline,
    // so reverting a change clears the dirty flag instead of leaving a phantom.
    val baselineName: String = "",
    val baselineDescription: String = "",
    val baselineColor: Int = DEFAULT_COLOR,
    val baselineIcon: String? = null,
    val baselineParentId: ProjectId? = null,
) {
    val isDirty: Boolean
        get() = name.text != baselineName ||
            description.text != baselineDescription ||
            color != baselineColor ||
            icon != baselineIcon ||
            parentId != baselineParentId
    companion object {
        const val DEFAULT_COLOR = 0xFF1976D2.toInt() // blue
    }

    val isEditMode: Boolean get() = projectId != null

    /**
     * The name as the field's source.
     *
     * The editor seeds the name once, when the project is loaded, and afterwards holds what the
     * user typed, so it never replaces the field's value with one the field never produced: the
     * field's echo guard needs no lifting, and there is no counter to read.
     *
     * The promise lives in the type: the only ways to write the value are the field's own
     * text, a seed and a clear.
     */
    val nameSource: StaticText get() = StaticText(name.text)

    /**
     * The description as the field's source; see [nameSource] for why no counter is needed.
     */
    val descriptionSource: StaticText get() = StaticText(description.text)
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
