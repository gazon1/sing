package com.singularity.todo.feature.proposals.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A value the user may change before the change is applied.
 *
 * @property text The value as it reads now — what the field is filled with.
 * @property change The same change with the value replaced — or why it cannot be.
 */
class EditableProposal(val text: String, val change: (String) -> ReadResult<ProposalItemKind>)

/**
 * The change a single [ProposalItem] asks for.
 *
 * This is the **only** place the proposal vocabulary is enumerated, and each variant
 * carries its own behaviour: what the user reads on the card ([humanSummary]), the
 * identity the rejection fingerprint compares ([fingerprintTarget]) and whether the
 * user may change the value before it is applied ([editableProposal]). A new kind is
 * therefore a new kind — not a new kind plus a summary somewhere else plus a
 * fingerprint somewhere else, three edits that have to be found by whoever adds it.
 *
 * The payload of the collection-shaped variants is kept as plain strings rather than
 * domain ids: the model proposes *text* ("milk", "chore"), and resolution to real
 * ids is a precondition check at apply time, not something the model can be trusted
 * to have done correctly.
 */
@Serializable
sealed interface ProposalItemKind {

    /**
     * What the user reads on the card.
     *
     * The line says what will happen rather than which variant of the domain type was
     * chosen — and it says *which value*, because a proposal nobody can read the
     * value of is a proposal the user approves blind.
     */
    val humanSummary: String

    /**
     * A stable, order-independent identity for this change.
     *
     * Feeds the rejection fingerprint, so a model which ignores the "recently rejected"
     * list in its prompt still cannot re-propose something already turned down.
     *
     * Order-independent by construction: `AddTags(["a","b"])` and `AddTags(["b","a"])`
     * are the same proposal and must not both survive a rejection.
     */
    val fingerprintTarget: String

    /**
     * Why this change cannot be read, when its stored value no longer reads.
     *
     * A value written by a build that knew the field is a change the user can
     * still see and turn down — with the reason it cannot be applied, next to
     * it. The change is not dropped: the user has to be able to decide about
     * it, and a change that vanishes is a change nobody decided about.
     */
    val readFailure: String?
        get() = null

    /**
     * The value the user may change before the change is applied — or null when the
     * change carries nothing a person would type.
     *
     * The behaviour lives here, in the kind, so that adding a kind is one decision in
     * one place: a kind that says null is a kind nobody can edit, and a kind that
     * says otherwise is a kind with a field the user can fill in.
     */
    fun editableProposal(): EditableProposal?

    /**
     * Set a field on the owning task.
     *
     * The value carries its own type, so the field and the value cannot disagree.
     */
    @Serializable
    @SerialName("SetTaskField")
    data class SetTaskField(val value: TaskValue) : ProposalItemKind {
        override val humanSummary: String = value.summary
        override val fingerprintTarget: String = value.fieldName
        override val readFailure: String?
            get() = value.readFailure
        override fun editableProposal(): EditableProposal? = value.editableValue()?.let { editable ->
            EditableProposal(editable.text) { newText ->
                editable.change(newText).map { newValue -> SetTaskField(newValue) }
            }
        }
    }

    /**
     * The owning task's tags become exactly [tagIds].
     *
     * A set, not an addition: `AddTags` and `RemoveTags` say what to add and what
     * to take away, and neither can say "the task carries these tags and no
     * others". A tool that rewrites a task's tags has to be able to say that.
     */
    @Serializable
    @SerialName("SetTaskTags")
    data class SetTaskTags(val tagIds: List<String>) : ProposalItemKind {
        override val humanSummary: String = "Set the tags"
        override val fingerprintTarget: String = tagIds.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Add each of these tag names to the task. Unresolvable names are skipped. */
    @Serializable
    @SerialName("AddTags")
    data class AddTags(val names: List<String>) : ProposalItemKind {
        override val humanSummary: String = "Add tags"
        override val fingerprintTarget: String = names.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Remove each of these tag ids from the task. Also records tag suppression. */
    @Serializable
    @SerialName("RemoveTags")
    data class RemoveTags(val tagIds: List<String>) : ProposalItemKind {
        override val humanSummary: String = "Remove tags"
        override val fingerprintTarget: String = tagIds.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Append these texts as new checklist items. */
    @Serializable
    @SerialName("AddChecklistItems")
    data class AddChecklistItems(val texts: List<String>) : ProposalItemKind {
        override val humanSummary: String = "${texts.size} checklist item(s)"
        override val fingerprintTarget: String = texts.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Create these titles as subtasks of the owning task. */
    @Serializable
    @SerialName("AddSubtasks")
    data class AddSubtasks(val titles: List<String>) : ProposalItemKind {
        override val humanSummary: String = "${titles.size} subtask(s)"
        override val fingerprintTarget: String = titles.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Log these as time entries against the owning task. */
    @Serializable
    @SerialName("AddTimeEntries")
    data class AddTimeEntries(val entries: List<ProposedTimeEntry>) : ProposalItemKind {
        override val humanSummary: String = "Add time entry"
        override val fingerprintTarget: String = entries
            .map { "${it.startedAt}-${it.endedAt}" }
            .sorted()
            .joinToString("|")
        override fun editableProposal(): EditableProposal? = null
    }

    /** Set a field on the owning note. */
    @Serializable
    @SerialName("SetNoteField")
    data class SetNoteField(val value: NoteValue) : ProposalItemKind {
        override val humanSummary: String = value.summary
        override val fingerprintTarget: String = value.fieldName
        override val readFailure: String?
            get() = value.readFailure
        override fun editableProposal(): EditableProposal? = value.editableValue()?.let { editable ->
            EditableProposal(editable.text) { newText ->
                editable.change(newText).map { newValue -> SetNoteField(newValue) }
            }
        }
    }

    /** Set a field on the owning project. */
    @Serializable
    @SerialName("SetProjectField")
    data class SetProjectField(val value: ProjectValue) : ProposalItemKind {
        override val humanSummary: String = value.summary
        override val fingerprintTarget: String = value.fieldName
        override val readFailure: String?
            get() = value.readFailure
        override fun editableProposal(): EditableProposal? = value.editableValue()?.let { editable ->
            EditableProposal(editable.text) { newText ->
                editable.change(newText).map { newValue -> SetProjectField(newValue) }
            }
        }
    }

    /** Set a field on the owning tag. */
    @Serializable
    @SerialName("SetTagField")
    data class SetTagField(val value: TagValue) : ProposalItemKind {
        override val humanSummary: String = value.summary
        override val fingerprintTarget: String = value.fieldName
        override val readFailure: String?
            get() = value.readFailure
        override fun editableProposal(): EditableProposal? = value.editableValue()?.let { editable ->
            EditableProposal(editable.text) { newText ->
                editable.change(newText).map { newValue -> SetTagField(newValue) }
            }
        }
    }

    /**
     * Create a tag with these fields.
     *
     * A tag is the last thing the model could create without the user seeing it: the
     * write tools proposed, and creating a tag wrote. Now it proposes like the rest,
     * and the tag's id is named by the proposal — the id the tag *will* have.
     */
    @Serializable
    @SerialName("CreateTag")
    data class CreateTag(val tag: ProposedTag) : ProposalItemKind {
        override val humanSummary: String = "Create a tag «${tag.name.asCardValue()}»"
        override val fingerprintTarget: String = tag.name.trim().lowercase()
        override fun editableProposal(): EditableProposal = EditableProposal(tag.name) { newText ->
            val name = newText.trim()
            if (name.isEmpty()) {
                ReadResult.Rejected("A tag needs a name.")
            } else {
                ReadResult.Ok(CreateTag(tag.copy(name = name)))
            }
        }
    }

    /**
     * Create a task with these fields.
     *
     * A creation is a change like any other, and it gets the same treatment: the
     * proposal holds the task until the user says yes. The task does not exist
     * yet, so the target id of the item is the id the task *will* have.
     */
    @Serializable
    @SerialName("CreateTask")
    data class CreateTask(val task: ProposedTask) : ProposalItemKind {
        override val humanSummary: String = "Create a task «${task.title.asCardValue()}»"
        override val fingerprintTarget: String = task.title.trim().lowercase()
        override fun editableProposal(): EditableProposal = EditableProposal(task.title) { newText ->
            val title = newText.trim()
            if (title.isEmpty()) {
                ReadResult.Rejected("A task needs a title.")
            } else {
                ReadResult.Ok(CreateTask(task.copy(title = title)))
            }
        }
    }

    /** Create a project with these fields. */
    @Serializable
    @SerialName("CreateProject")
    data class CreateProject(val project: ProposedProject) : ProposalItemKind {
        override val humanSummary: String = "Create a project «${project.name.asCardValue()}»"
        override val fingerprintTarget: String = project.name.trim().lowercase()
        override fun editableProposal(): EditableProposal = EditableProposal(project.name) { newText ->
            val name = newText.trim()
            if (name.isEmpty()) {
                ReadResult.Rejected("A project needs a name.")
            } else {
                ReadResult.Ok(CreateProject(project.copy(name = name)))
            }
        }
    }

    /** Create a note or folder with these fields. */
    @Serializable
    @SerialName("CreateNote")
    data class CreateNote(val note: ProposedNote) : ProposalItemKind {
        override val humanSummary: String = "Create a note «${note.title.asCardValue()}»"
        override val fingerprintTarget: String = note.title.trim().lowercase()
        override fun editableProposal(): EditableProposal = EditableProposal(note.title) { newText ->
            val title = newText.trim()
            if (title.isEmpty()) {
                ReadResult.Rejected("A note needs a title.")
            } else {
                ReadResult.Ok(CreateNote(note.copy(title = title)))
            }
        }
    }

    /** Delete the owning note. */
    @Serializable
    @SerialName("DeleteNote")
    data class DeleteNote(val reason: String? = null) : ProposalItemKind {
        override val humanSummary: String = "Delete note"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }

    /**
     * Extract task-like actions from a note's content.
     *
     * [actions] is a list of action texts proposed to become tasks.
     */
    @Serializable
    @SerialName("ExtractActions")
    data class ExtractActions(val actions: List<String>) : ProposalItemKind {
        override val humanSummary: String = "${actions.size} action(s) from note"
        override val fingerprintTarget: String = actions.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Soft-delete (archive) the owning task. */
    @Serializable
    @SerialName("DeleteTask")
    data class DeleteTask(val reason: String? = null) : ProposalItemKind {
        override val humanSummary: String = "Delete task"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }

    /** Soft-delete (archive) the owning project. */
    @Serializable
    @SerialName("DeleteProject")
    data class DeleteProject(val reason: String? = null) : ProposalItemKind {
        override val humanSummary: String = "Delete project"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }

    /** Delete the owning tag. */
    @Serializable
    @SerialName("DeleteTag")
    data class DeleteTag(val reason: String? = null) : ProposalItemKind {
        override val humanSummary: String = "Delete tag"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }
}

/** Trimmed, blank-free, de-duplicated, sorted — the canonical form for hashing. */
private fun List<String>.normalized(): String =
    map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")
