package com.singularity.todo.feature.projects.presentation.viewmodel

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure editable-draft state for [ProjectDetailViewModel].
 *
 * Owns the in-progress name and description edits with dirty-tracking.
 * The [seed] operation is idempotent — it only initializes from the loaded project
 * if the draft has not yet been initialized (prevents overwriting user's edits
 * from a prior editing session after screen rotation).
 */
@Stable
class ProjectDetailDraftState(initial: ProjectDetailDraft = ProjectDetailDraft.empty()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ProjectDetailDraft> = _state.asStateFlow()
    val current: ProjectDetailDraft get() = _state.value

    /** Idempotent seed — only sets from loaded project if not already initialized. */
    fun seed(name: String, description: String) {
        if (_state.value.initialized) return
        _state.value = ProjectDetailDraft(
            name = name,
            description = description,
            originalName = name,
            originalDescription = description,
            initialized = true,
        )
    }

    fun setName(name: String) {
        _state.value = _state.value.copy(name = name)
    }
    fun setDescription(description: String) {
        _state.value = _state.value.copy(description = description)
    }
}

data class ProjectDetailDraft(
    val name: String,
    val description: String,
    val originalName: String,
    val originalDescription: String,
    val initialized: Boolean = false,
) {
    companion object {
        fun empty() = ProjectDetailDraft("", "", "", "")
    }
    val isDirty: Boolean get() = name != originalName || description != originalDescription
}
