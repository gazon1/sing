package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure editable-draft state for [TaskDetailViewModel].
 *
 * Owns the in-progress title and description edits with dirty-tracking.
 * The [seed] operation is idempotent — it only initializes from the loaded task
 * if the draft has not yet been initialized (prevents overwriting user's edits
 * from a prior editing session after screen rotation).
 */
@Stable
class TaskDetailDraftState(
    initial: TaskDetailDraft = TaskDetailDraft.empty(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<TaskDetailDraft> = _state.asStateFlow()
    val current: TaskDetailDraft get() = _state.value

    /** Idempotent seed — only sets from loaded task if not already initialized. */
    fun seed(title: String, description: String) {
        if (_state.value.initialized) return
        _state.value = TaskDetailDraft(
            title = title,
            description = description,
            originalTitle = title,
            originalDescription = description,
            initialized = true,
        )
    }

    fun setTitle(title: String) { _state.value = _state.value.copy(title = title) }
    fun setDescription(description: String) { _state.value = _state.value.copy(description = description) }
}

data class TaskDetailDraft(
    val title: String,
    val description: String,
    val originalTitle: String,
    val originalDescription: String,
    val initialized: Boolean = false,
) {
    companion object { fun empty() = TaskDetailDraft("", "", "", "") }
    val isDirty: Boolean get() = title != originalTitle || description != originalDescription
}
