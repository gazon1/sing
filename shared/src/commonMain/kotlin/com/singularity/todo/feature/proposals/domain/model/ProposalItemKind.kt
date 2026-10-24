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
 * A change about a task.
 *
 * The group a kind belongs to is what routes it: the planner has one function per
 * group, and each of them switches over the group exhaustively — so a kind that
 * joins a group and is not handled there does not compile.
 *
 * The group is not a note in the documentation: [ProposalTarget.OnTask] carries a
 * change of this group, and the compiler refuses the change that claims a group it
 * does not belong to.
 */
sealed interface TaskBoundKind

/** A change about a note. See [TaskBoundKind] for what a group is. */
sealed interface NoteBoundKind

/** A change about a project. See [TaskBoundKind] for what a group is. */
sealed interface ProjectBoundKind

/** A change about a tag. See [TaskBoundKind] for what a group is. */
sealed interface TagBoundKind

/**
 * A change that brings something into being.
 *
 * There is no row to read before it is applied — the change names the id the thing
 * *will* have — so the group is its own.
 *
 * A creation knows what it brings into being, and says so: the thing it creates is
 * the target the proposal is addressed to. A creation that did not say would leave
 * the caller to type the kind of target by hand — which is the mistake this whole
 * arrangement exists to make impossible.
 */
sealed interface CreationBoundKind {

    /** What this change brings into being. */
    val targetKind: ProposalTargetKind
}

/**
 * What a change is about.
 *
 * ## Why the target carries the change itself
 *
 * The routing used to be a list of questions asked about the kind: "is this a
 * creation?", "is this about a note?" — questions asked in a use case, about a
 * vocabulary declared in the model. A kind that arrived and answered none of them
 * fell through to the branch that assumed a task, and the mistake showed up as a
 * runtime error about an "unexpected kind" long after the change was written.
 *
 * So the change says what it is about, and the target carries the change with it:
 * a target that names a group can only be built from a change of that group, and
 * the compiler says so. A kind that arrives without a target does not compile at
 * all, and a kind that arrives with the wrong one does not compile either.
 */
sealed interface ProposalTarget {

    /**
     * What this change is about, in the word the row is addressed by.
     *
     * The target is the one place that knows: a change about a task says so, and a
     * creation says what it brings into being. So a caller that has to write down
     * what the change is about has nothing left to write — and a caller that gets it
     * wrong cannot, because there is no second declaration to get wrong.
     */
    val targetKind: ProposalTargetKind

    /** The change brings something into being. There is no row to read. */
    data class Creation(val kind: CreationBoundKind) : ProposalTarget {
        /** What this change brings into being — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind get() = kind.targetKind
    }

    /** The change touches a task. */
    data class OnTask(val kind: TaskBoundKind) : ProposalTarget {
        /** What this change is about — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind get() = ProposalTargetKind.Task
    }

    /** The change touches a note. */
    data class OnNote(val kind: NoteBoundKind) : ProposalTarget {
        /** What this change is about — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind get() = ProposalTargetKind.Note
    }

    /** The change touches a project. */
    data class OnProject(val kind: ProjectBoundKind) : ProposalTarget {
        /** What this change is about — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind get() = ProposalTargetKind.Project
    }

    /** The change touches a tag. */
    data class OnTag(val kind: TagBoundKind) : ProposalTarget {
        /** What this change is about — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind get() = ProposalTargetKind.Tag
    }

    /**
     * The change cannot be applied by this build.
     *
     * The reason belongs to the change, not to the target: the kind that says it
     * cannot be read says why, and the card shows that reason.
     */
    data object Unreadable : ProposalTarget {
        /**
         * What this change is about — see [ProposalTarget].
         *
         * A change this build cannot read does not say what it is about, and it is
         * not known to be about a task either. The name it is given here is the
         * absence of one, not a kind of target it was written with.
         */
        override val targetKind: ProposalTargetKind
            get() = ProposalTargetKind.Unknown(UNREADABLE_TARGET_KIND)
    }
}

/**
 * The name given to a kind of target nobody knows.
 *
 * A row written by a newer build carries the kind it was written with, and the
 * mapper reads that. This name is only for a change that is not read at all.
 */
private const val UNREADABLE_TARGET_KIND = "UNKNOWN"

/**
 * The change a single [ProposalItem] asks for.
 *
 * This is the **only** place the proposal vocabulary is enumerated, and each variant
 * carries its own behaviour: what the user reads on the card ([humanSummary]), the
 * identity the rejection fingerprint compares ([fingerprintTarget]), what the change
 * is about ([target]) and whether the user may change the value before it is applied
 * ([editableProposal]). A new kind is therefore a new kind — not a new kind plus a
 * summary somewhere else plus a fingerprint somewhere else plus a place that has to
 * know what to do with it, four edits that have to be found by whoever adds it.
 *
 * The payload of the collection-shaped variants is kept as plain strings rather than
 * domain ids: the model proposes *text* ("milk", "chore"), and resolution to real
 * ids is a precondition check at apply time, not something the model can be trusted
 * to have done correctly.
 */
@Serializable
sealed interface ProposalItemKind {

    /**
     * What this change is about.
     *
     * The answer is what routes the change: the planner has one function per group,
     * and the use case reads the row the group names. So a change that does not say
     * what it is about does not compile — and neither does a change that says
     * something it is not.
     */
    val target: ProposalTarget

    /**
     * The tag names this change carries, when it carries any.
     *
     * The names are what the model proposed; the ids are what the task carries, and
     * the resolution of one into the other is a precondition check at apply time.
     * So the change says what it carries, and the caller resolves it.
     */
    val tagNames: Set<String>
        get() = when (this) {
            is AddTags -> names.toSet()
            else -> emptySet()
        }

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
    data class SetTaskField(val value: TaskValue) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
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
    data class SetTaskTags(val tagIds: List<String>) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
        override val humanSummary: String = "Set the tags"
        override val fingerprintTarget: String = tagIds.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Add each of these tag names to the task. Unresolvable names are skipped. */
    @Serializable
    @SerialName("AddTags")
    data class AddTags(val names: List<String>) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
        override val humanSummary: String = "Add tags"
        override val fingerprintTarget: String = names.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Remove each of these tag ids from the task. Also records tag suppression. */
    @Serializable
    @SerialName("RemoveTags")
    data class RemoveTags(val tagIds: List<String>) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
        override val humanSummary: String = "Remove tags"
        override val fingerprintTarget: String = tagIds.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Append these texts as new checklist items. */
    @Serializable
    @SerialName("AddChecklistItems")
    data class AddChecklistItems(val texts: List<String>) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
        override val humanSummary: String = "${texts.size} checklist item(s)"
        override val fingerprintTarget: String = texts.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Create these titles as subtasks of the owning task. */
    @Serializable
    @SerialName("AddSubtasks")
    data class AddSubtasks(val titles: List<String>) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
        override val humanSummary: String = "${titles.size} subtask(s)"
        override val fingerprintTarget: String = titles.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Log these as time entries against the owning task. */
    @Serializable
    @SerialName("AddTimeEntries")
    data class AddTimeEntries(val entries: List<ProposedTimeEntry>) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
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
    data class SetNoteField(val value: NoteValue) :
        ProposalItemKind,
        NoteBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnNote(this)
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
    data class SetProjectField(val value: ProjectValue) :
        ProposalItemKind,
        ProjectBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnProject(this)
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
    data class SetTagField(val value: TagValue) :
        ProposalItemKind,
        TagBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTag(this)
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
    data class CreateTag(val tag: ProposedTag) :
        ProposalItemKind,
        CreationBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.Creation(this)

        /** What this change brings into being — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind = ProposalTargetKind.Tag
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
    data class CreateTask(val task: ProposedTask) :
        ProposalItemKind,
        CreationBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.Creation(this)

        /** What this change brings into being — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind = ProposalTargetKind.Task
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
    data class CreateProject(val project: ProposedProject) :
        ProposalItemKind,
        CreationBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.Creation(this)

        /** What this change brings into being — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind = ProposalTargetKind.Project
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
    data class CreateNote(val note: ProposedNote) :
        ProposalItemKind,
        CreationBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.Creation(this)

        /** What this change brings into being — see [ProposalTarget]. */
        override val targetKind: ProposalTargetKind = ProposalTargetKind.Note
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
    data class DeleteNote(val reason: String? = null) :
        ProposalItemKind,
        NoteBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnNote(this)
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
    data class ExtractActions(val actions: List<String>) :
        ProposalItemKind,
        NoteBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnNote(this)
        override val humanSummary: String = "${actions.size} action(s) from note"
        override val fingerprintTarget: String = actions.normalized()
        override fun editableProposal(): EditableProposal? = null
    }

    /** Soft-delete (archive) the owning task. */
    @Serializable
    @SerialName("DeleteTask")
    data class DeleteTask(val reason: String? = null) :
        ProposalItemKind,
        TaskBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTask(this)
        override val humanSummary: String = "Delete task"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }

    /** Soft-delete (archive) the owning project. */
    @Serializable
    @SerialName("DeleteProject")
    data class DeleteProject(val reason: String? = null) :
        ProposalItemKind,
        ProjectBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnProject(this)
        override val humanSummary: String = "Delete project"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }

    /** Delete the owning tag. */
    @Serializable
    @SerialName("DeleteTag")
    data class DeleteTag(val reason: String? = null) :
        ProposalItemKind,
        TagBoundKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.OnTag(this)
        override val humanSummary: String = "Delete tag"
        override val fingerprintTarget: String = ""
        override fun editableProposal(): EditableProposal? = null
    }

    /**
     * A change this build cannot read.
     *
     * The row was written by a build that knew a kind this one does not, or the
     * blob is not a blob this build can make sense of at all. The change is still
     * a change: it stays on the card, with the reason it cannot be applied, and
     * the user can turn it down. A change that vanishes from the card is a change
     * nobody decided about — and the user is the only one who can decide it.
     *
     * What it cannot be is applied: there is nothing to apply. The reason says so
     * where the user reads it, instead of the row disappearing.
     *
     * @property raw The stored blob, exactly as it was found. It is kept so the
     *   change can be written back unchanged — a row this build cannot read must
     *   not become a row this build *rewrites* into something else.
     * @property reason Why this change cannot be read, in the words the user reads.
     *   The reader knows more than one reason exists — a blob that is not a blob at
     *   all, a kind this build does not know, a field this build does not know — and
     *   a change that carries the same sentence for three different facts carries
     *   no fact at all.
     */
    @Serializable
    @SerialName("Unknown")
    data class Unknown(val raw: String, val reason: String = UNREADABLE_REASON) : ProposalItemKind {
        /** What this change is about — see [ProposalTarget]. */
        override val target: ProposalTarget = ProposalTarget.Unreadable
        override val humanSummary: String = "A change this version cannot read"
        override val fingerprintTarget: String = raw
        override val readFailure: String? = reason
        override fun editableProposal(): EditableProposal? = null
    }

    companion object {

        /**
         * The reason a change carries when nothing more specific is known.
         *
         * The reader names the reason it knows; this is the one it reaches for when
         * the blob was written by a build that knew something this one does not.
         */
        const val UNREADABLE_REASON =
            "This change was written by another version of the app, and this one cannot read it."
    }
}

/** Trimmed, blank-free, de-duplicated, sorted — the canonical form for hashing. */
private fun List<String>.normalized(): String =
    map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")
