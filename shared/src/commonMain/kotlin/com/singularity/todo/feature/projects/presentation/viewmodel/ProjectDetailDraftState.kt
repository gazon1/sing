package com.singularity.todo.feature.projects.presentation.viewmodel

import androidx.compose.runtime.Stable
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.text.Revisioned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure editable-draft state for [ProjectDetailViewModel].
 *
 * Owns the in-progress name and description edits with dirty-tracking.
 *
 * ## The seed is no longer idempotent
 *
 * The seed used to be idempotent: it initialised the draft once and ignored
 * every later call, so a project rewritten from outside — a sync from another
 * device, a rename made on another screen — never reached the field. The
 * screen kept showing the old name, and the user's next keystroke wrote the
 * old name back over the new one.
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
 * The revision counts *replacements*, never edits. A revision that moved on
 * every keystroke would be a second copy of the value. It is what the bridged
 * text field reads to tell a replacement from an echo — see
 * `rememberBridgedTextFieldState` and the rewrite contract in
 * `.agents/skills/singularity-todo-text-field-states/SKILL.md`.
 *
 * The count lives *inside* the value, in a [Revisioned]: a draft that held the
 * name and the count in two fields could replace the name and leave the count
 * where it was, and the field would treat the replacement as an echo of its own
 * last push. One object, one way to change it.
 */
@Stable
class ProjectDetailDraftState(initial: ProjectDetailDraft = ProjectDetailDraft.empty()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ProjectDetailDraft> = _state.asStateFlow()
    val current: ProjectDetailDraft get() = _state.value

    /**
     * Seeds the draft from the project the repository holds.
     *
     * @param name the project's name as the repository holds it.
     * @param description the project's description as the repository holds it.
     */
    fun seed(name: String, description: String) {
        val current = _state.value
        if (!current.initialized) {
            _state.value = ProjectDetailDraft(
                name = Revisioned.initial(name),
                description = Revisioned.initial(description),
                originalName = name,
                originalDescription = description,
                initialized = true,
            )
            return
        }
        if (current.name.value == name && current.description.value == description) {
            // The echo of our own save: the repository holds what the draft holds.
            // The baseline moves with it, so the draft is clean again — and a later
            // rewrite from outside is not refused as "the user is typing".
            _state.value = current.copy(originalName = name, originalDescription = description)
            return
        }
        // The user is typing: their text wins over the other writer's.
        if (current.isDirty) return
        // A rewrite from outside: take it, and say that it happened.
        _state.value = current.copy(
            name = current.name.replacedBy(name),
            description = current.description.replacedBy(description),
            originalName = name,
            originalDescription = description,
        )
    }

    fun setName(name: String) {
        _state.value = _state.value.copy(name = _state.value.name.typed(name))
    }
    fun setDescription(description: String) {
        _state.value = _state.value.copy(description = _state.value.description.typed(description))
    }
}

/**
 * The project's editable draft.
 *
 * @property name the name as the field shows it, and the count of the times it was
 *   replaced from outside.
 * @property description the description as the field shows it, and the count of the
 *   times it was replaced from outside.
 * @property originalName the name as the repository last accepted it.
 * @property originalDescription the description as the repository last accepted it.
 * @property initialized whether the draft has been seeded at all.
 */
data class ProjectDetailDraft(
    val name: Revisioned<String>,
    val description: Revisioned<String>,
    val originalName: String,
    val originalDescription: String,
    val initialized: Boolean = false,
) {
    companion object {
        fun empty() = ProjectDetailDraft(
            name = Revisioned.initial(""),
            description = Revisioned.initial(""),
            originalName = "",
            originalDescription = "",
        )
    }
    val isDirty: Boolean get() =
        name.value != originalName || description.value != originalDescription

    /**
     * The name as the field's source.
     *
     * The ViewModel observes the project, so the project can be rewritten from outside the
     * screen — a sync from another device, a rename made on another one — and the rewrite
     * can land on a name the field itself produced. The draft counts the replacements, and
     * the type says the count is there: a field that holds this source cannot be wired as
     * if the name were a value nobody else writes.
     */
    val nameSource: OwnedText get() = OwnedText(name.value, name.revision)

    /** The description as the field's source; see [nameSource] for why the count is there. */
    val descriptionSource: OwnedText get() = OwnedText(description.value, description.revision)
}
