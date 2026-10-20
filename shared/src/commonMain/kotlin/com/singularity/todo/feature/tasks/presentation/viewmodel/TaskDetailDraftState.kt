package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure editable-draft state for [TaskDetailCoordinator].
 *
 * Owns the in-progress title and description edits with dirty-tracking.
 *
 * ## The seed is no longer idempotent
 *
 * The seed used to be idempotent: it initialised the draft once and ignored
 * every later call. That made the draft a snapshot of the task as it was when
 * the screen opened, and a task rewritten from outside — a sync from another
 * device, a change made on another screen — never reached the field. The
 * screen kept showing the old title, and the user's next keystroke wrote the
 * old title back over the new one.
 *
 * The seed now tells three cases apart:
 *
 * - **The first call** initialises the draft.
 * - **The echo of our own save** — the repository holds exactly what the draft
 *   holds — moves the baseline, so the draft is clean again, and moves no
 *   revision.
 * - **A rewrite from outside** — the draft holds something else and holds no
 *   unsaved edits — is taken, and the [revision] moves so the field knows the
 *   new text is a *replacement* and not a keystroke.
 *
 * **The user's unsaved edits win.** A dirty draft is never re-seeded: the
 * rewrite waits for the user, and the next write carries the user's text over
 * it. The other writer has no stake in the text; the user does.
 *
 * ## The revision
 *
 * [TaskDetailDraft.revision] counts *replacements*, never edits. A revision
 * that moved on every keystroke would be a second copy of the value. It is
 * what the bridged text field reads to tell a replacement from an echo — see
 * `rememberBridgedTextFieldState` and the rewrite contract in
 * `.agents/skills/singularity-todo-text-field-states/SKILL.md`.
 */
@Stable
class TaskDetailDraftState(initial: TaskDetailDraft = TaskDetailDraft.empty()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<TaskDetailDraft> = _state.asStateFlow()
    val current: TaskDetailDraft get() = _state.value

    /**
     * Seeds the draft from the task the repository holds.
     *
     * Called once per task change, not once per unrelated child update.
     *
     * @param title the task's title as the repository holds it.
     * @param description the task's description as the repository holds it.
     */
    fun seed(title: String, description: String) {
        val current = _state.value
        if (!current.initialized) {
            _state.value = TaskDetailDraft(
                title = title,
                description = description,
                originalTitle = title,
                originalDescription = description,
                initialized = true,
            )
            return
        }
        if (current.title == title && current.description == description) {
            // The echo of our own save: the repository holds what the draft holds.
            // The baseline moves with it, so the draft is clean again — and a later
            // rewrite from outside is not refused as "the user is typing".
            _state.value = current.copy(originalTitle = title, originalDescription = description)
            return
        }
        // The user is typing: their text wins over the other writer's.
        if (current.isDirty) return
        // A rewrite from outside: take it, and say that it happened.
        _state.value = current.copy(
            title = title,
            description = description,
            originalTitle = title,
            originalDescription = description,
            revision = current.revision + 1,
        )
    }

    fun setTitle(title: String) {
        _state.value = _state.value.copy(title = title)
    }
    fun setDescription(description: String) {
        _state.value = _state.value.copy(description = description)
    }
}

/**
 * The task's editable draft.
 *
 * @property title the title as the field shows it.
 * @property description the description as the field shows it.
 * @property originalTitle the title as the repository last accepted it.
 * @property originalDescription the description as the repository last accepted it.
 * @property initialized whether the draft has been seeded at all.
 * @property revision how many times the content was *replaced* from outside.
 *   It never moves for a keystroke, and it never moves for the echo of our own
 *   save.
 */
data class TaskDetailDraft(
    val title: String,
    val description: String,
    val originalTitle: String,
    val originalDescription: String,
    val initialized: Boolean = false,
    val revision: Long = 0,
) {
    companion object {
        fun empty() = TaskDetailDraft("", "", "", "")
    }
    val isDirty: Boolean get() = title != originalTitle || description != originalDescription
}
