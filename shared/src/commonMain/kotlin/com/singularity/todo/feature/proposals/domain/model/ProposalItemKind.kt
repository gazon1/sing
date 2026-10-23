package com.singularity.todo.feature.proposals.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The task fields a [ProposalItemKind.SetTaskField] item may propose to change.
 *
 * Modelled as an enum rather than six separate `ProposalItemKind` variants so that
 * adding a settable field is a one-line change here instead of a new `when` arm in
 * [com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase].
 */
@Serializable
enum class TaskField {
    @SerialName("title")
    Title,

    @SerialName("description")
    Description,

    @SerialName("priority")
    Priority,

    @SerialName("due_date")
    DueDate,

    @SerialName("status")
    Status,

    @SerialName("estimate_minutes")
    EstimateMinutes,

    /** The kind of work — `Task`, `Note`, and whatever else the app calls a kind. */
    @SerialName("kind")
    Kind,

    /** The time of day the task is due, as `HH:mm`. */
    @SerialName("due_time")
    DueTime,

    /** The project the task belongs to, as a project id. An empty value moves it out of every project. */
    @SerialName("project")
    Project,

    /** Whether the task is deferred — `true` or `false`. */
    @SerialName("someday")
    Someday,
}

/**
 * The note fields a [ProposalItemKind.SetNoteField] item may propose to change.
 *
 * Mirrors [TaskField] for notes, enabling the same enum-based dispatch pattern.
 */
@Serializable
enum class NoteField {
    @SerialName("title")
    Title,

    @SerialName("body")
    Body,

    /** Summary of the note content, written to the body as HTML. */
    @SerialName("summary")
    Summary,
}

/**
 * The project fields a [ProposalItemKind.SetProjectField] item may propose to change.
 *
 * A project had no shape of its own in the proposal vocabulary until the write
 * tools were put on the proposal path: `SetTaskField` and `SetNoteField` could
 * change a task and a note, and a project could only be deleted. So "change the
 * project's name" was impossible not because anybody forgot, but because there
 * was no kind for it.
 */
@Serializable
enum class ProjectField {
    @SerialName("name")
    Name,

    @SerialName("description")
    Description,

    /** The project's colour, as an ARGB integer. */
    @SerialName("color")
    Color,

    /** The project's icon name. */
    @SerialName("icon")
    Icon,
}

/**
 * A time range proposed by [ProposalItemKind.AddTimeEntries].
 *
 * @param startedAt Epoch millis when the work started.
 * @param endedAt Epoch millis when it ended. Always after [startedAt].
 * @param note Optional free-text note describing the work.
 */
@Serializable
data class ProposedTimeEntry(val startedAt: Long, val endedAt: Long, val note: String? = null)

/**
 * The task a [ProposalItemKind.CreateTask] item proposes to bring into being.
 *
 * The values are the raw strings the model produced, and the planner parses and
 * validates them before anything is claimed — so an unparseable date leaves the
 * proposal pending instead of writing a task with a broken date.
 */
@Serializable
data class ProposedTask(
    val title: String,
    val description: String? = null,
    val priority: String = "None",
    val kind: String = "Task",
    val projectId: String? = null,
    val parentTaskId: String? = null,
    val tagIds: List<String> = emptyList(),
    /** `YYYY-MM-DD`. */
    val dueDate: String? = null,
    /** `HH:mm`. */
    val dueTime: String? = null,
    val someday: Boolean = false,
)

/**
 * The project a [ProposalItemKind.CreateProject] item proposes to bring into being.
 */
@Serializable
data class ProposedProject(
    val name: String,
    val description: String? = null,
    val color: Int = 0xFF2196F3.toInt(),
    val icon: String? = null,
)

/**
 * The note a [ProposalItemKind.CreateNote] item proposes to bring into being.
 *
 * @property bodyHtml The body as canonical HTML — the form the notes are stored in.
 */
@Serializable
data class ProposedNote(
    val title: String = "",
    val bodyHtml: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: String? = null,
)

/**
 * The change a single [ProposalItem] asks for.
 *
 * This is the **only** place the proposal vocabulary is enumerated. Dispatch happens
 * in exactly one `when` — [com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase]
 * — so adding a variant forces the compiler to point at every site that must learn
 * about it.
 *
 * The payload of the collection-shaped variants is kept as plain strings rather than
 * domain ids: the model proposes *text* ("milk", "chore"), and resolution to real
 * ids is a precondition check at apply time, not something the model can be trusted
 * to have done correctly.
 */
@Serializable
sealed interface ProposalItemKind {

    /**
     * Set a scalar field on the owning task to [value].
     *
     * [value] is the raw string the model produced; the use case parses and validates
     * it against [field] before writing, so an unparseable value leaves the item
     * pending rather than writing garbage.
     */
    @Serializable
    @SerialName("SetTaskField")
    data class SetTaskField(val field: TaskField, val value: String) : ProposalItemKind

    /**
     * The owning task's tags become exactly [tagIds].
     *
     * A set, not an addition: `AddTags` and `RemoveTags` say what to add and what
     * to take away, and neither can say "the task carries these tags and no
     * others". A tool that rewrites a task's tags has to be able to say that.
     */
    @Serializable
    @SerialName("SetTaskTags")
    data class SetTaskTags(val tagIds: List<String>) : ProposalItemKind

    /** Add each of these tag names to the task. Unresolvable names are skipped. */
    @Serializable
    @SerialName("AddTags")
    data class AddTags(val names: List<String>) : ProposalItemKind

    /** Remove each of these tag ids from the task. Also records tag suppression. */
    @Serializable
    @SerialName("RemoveTags")
    data class RemoveTags(val tagIds: List<String>) : ProposalItemKind

    /** Append these texts as new checklist items. */
    @Serializable
    @SerialName("AddChecklistItems")
    data class AddChecklistItems(val texts: List<String>) : ProposalItemKind

    /** Create these titles as subtasks of the owning task. */
    @Serializable
    @SerialName("AddSubtasks")
    data class AddSubtasks(val titles: List<String>) : ProposalItemKind

    /** Log these as time entries against the owning task. */
    @Serializable
    @SerialName("AddTimeEntries")
    data class AddTimeEntries(val entries: List<ProposedTimeEntry>) : ProposalItemKind

    /**
     * Set a scalar field on the owning note to [value].
     *
     * [value] is the raw string the model produced; the use case parses and validates
     * it against [NoteField] before writing, so an unparseable value leaves the item
     * pending rather than writing garbage.
     */
    @Serializable
    @SerialName("SetNoteField")
    data class SetNoteField(val field: NoteField, val value: String) : ProposalItemKind

    /**
     * Set a scalar field on the owning project to [value].
     *
     * The value is the raw string the model produced; the planner parses and
     * validates it against [ProjectField] before writing.
     */
    @Serializable
    @SerialName("SetProjectField")
    data class SetProjectField(val field: ProjectField, val value: String) : ProposalItemKind

    /**
     * Create a task with these fields.
     *
     * A creation is a change like any other, and it gets the same treatment: the
     * proposal holds the task until the user says yes. The task does not exist
     * yet, so the target id of the item is the id the task *will* have.
     */
    @Serializable
    @SerialName("CreateTask")
    data class CreateTask(val task: ProposedTask) : ProposalItemKind

    /** Create a project with these fields. */
    @Serializable
    @SerialName("CreateProject")
    data class CreateProject(val project: ProposedProject) : ProposalItemKind

    /** Create a note or folder with these fields. */
    @Serializable
    @SerialName("CreateNote")
    data class CreateNote(val note: ProposedNote) : ProposalItemKind

    /** Delete the owning note. */
    @Serializable
    @SerialName("DeleteNote")
    data class DeleteNote(val reason: String? = null) : ProposalItemKind

    /**
     * Extract task-like actions from a note's content.
     *
     * [actions] is a list of action texts proposed to become tasks.
     */
    @Serializable
    @SerialName("ExtractActions")
    data class ExtractActions(val actions: List<String>) : ProposalItemKind

    /** Soft-delete (archive) the owning task. */
    @Serializable
    @SerialName("DeleteTask")
    data class DeleteTask(val reason: String? = null) : ProposalItemKind

    /** Soft-delete (archive) the owning project. */
    @Serializable
    @SerialName("DeleteProject")
    data class DeleteProject(val reason: String? = null) : ProposalItemKind

    /** Delete the owning tag. */
    @Serializable
    @SerialName("DeleteTag")
    data class DeleteTag(val reason: String? = null) : ProposalItemKind
}

/**
 * A stable, order-independent identity for this change.
 *
 * Feeds the rejection fingerprint, so a model which ignores the "recently rejected"
 * list in its prompt still cannot re-propose something already turned down.
 *
 * Order-independent by construction: `AddTags(["a","b"])` and `AddTags(["b","a"])`
 * are the same proposal and must not both survive a rejection.
 */
val ProposalItemKind.fingerprintTarget: String
    get() = when (this) {
        is ProposalItemKind.SetTaskField -> field.name

        is ProposalItemKind.SetTaskTags -> tagIds.normalized()

        is ProposalItemKind.AddTags -> names.normalized()

        is ProposalItemKind.RemoveTags -> tagIds.normalized()

        is ProposalItemKind.AddChecklistItems -> texts.normalized()

        is ProposalItemKind.AddSubtasks -> titles.normalized()

        is ProposalItemKind.AddTimeEntries ->
            entries
                .map { "${it.startedAt}-${it.endedAt}" }
                .sorted()
                .joinToString("|")

        is ProposalItemKind.SetNoteField -> field.name

        is ProposalItemKind.SetProjectField -> field.name

        is ProposalItemKind.CreateTask -> task.title.trim().lowercase()

        is ProposalItemKind.CreateProject -> project.name.trim().lowercase()

        is ProposalItemKind.CreateNote -> note.title.trim().lowercase()

        is ProposalItemKind.DeleteNote -> ""

        is ProposalItemKind.ExtractActions -> actions.normalized()

        is ProposalItemKind.DeleteTask -> ""

        is ProposalItemKind.DeleteProject -> ""

        is ProposalItemKind.DeleteTag -> ""
    }

/** Trimmed, blank-free, de-duplicated, sorted — the canonical form for hashing. */
private fun List<String>.normalized(): String =
    map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")

/**
 * Renders a [ProposalItemKind] to a compact JSON object for the `kind_json` column.
 *
 * Uses [buildJsonObject] rather than polymorphic serialization so the stored shape
 * stays flat and greppable in the database.
 */
fun ProposalItemKind.toJsonObject(): JsonObject = buildJsonObject {
    put("type", this@toJsonObject::class.simpleName ?: "Unknown")
    writeTaskPayload(this@toJsonObject, this)
    writeEntityPayload(this@toJsonObject, this)
    writeCreationPayload(this@toJsonObject, this)
    writeDeletionPayload(this@toJsonObject, this)
}

/**
 * The payload of the kinds a task carries.
 *
 * The payload is written by group rather than by one long `when`: the vocabulary
 * grew, and a single `when` over every kind is a function nobody can read — and
 * a function nobody can read is one that gets a new kind added to it wrongly.
 */
private fun writeTaskPayload(kind: ProposalItemKind, into: JsonObjectBuilder) {
    when (kind) {
        is ProposalItemKind.SetTaskField -> {
            into.put("field", kind.field.name)
            into.put("value", kind.value)
        }

        is ProposalItemKind.SetTaskTags -> into.put("tagIds", kind.tagIds.joinToString(LIST_SEPARATOR))

        is ProposalItemKind.AddTags -> into.put("names", kind.names.joinToString(LIST_SEPARATOR))

        is ProposalItemKind.RemoveTags -> into.put("tagIds", kind.tagIds.joinToString(LIST_SEPARATOR))

        is ProposalItemKind.AddChecklistItems -> into.put("texts", kind.texts.joinToString(LIST_SEPARATOR))

        is ProposalItemKind.AddSubtasks -> into.put("titles", kind.titles.joinToString(LIST_SEPARATOR))

        is ProposalItemKind.AddTimeEntries -> {
            into.put(
                "entries",
                kind.entries.joinToString(RECORD_SEPARATOR) {
                    "${it.startedAt}$FIELD_SEPARATOR${it.endedAt}$FIELD_SEPARATOR${it.note.orEmpty()}"
                },
            )
        }

        else -> Unit
    }
}

/** The payload of the kinds a note or a project carries. */
private fun writeEntityPayload(kind: ProposalItemKind, into: JsonObjectBuilder) {
    when (kind) {
        is ProposalItemKind.SetNoteField -> {
            into.put("field", kind.field.name)
            into.put("value", kind.value)
        }

        is ProposalItemKind.SetProjectField -> {
            into.put("field", kind.field.name)
            into.put("value", kind.value)
        }

        else -> Unit
    }
}

/** The payload of the kinds that bring something into being. */
private fun writeCreationPayload(kind: ProposalItemKind, into: JsonObjectBuilder) {
    when (kind) {
        is ProposalItemKind.CreateTask -> {
            into.put("title", kind.task.title)
            kind.task.description?.let { into.put("description", it) }
            into.put("priority", kind.task.priority)
            into.put("kind", kind.task.kind)
            kind.task.projectId?.let { into.put("projectId", it) }
            kind.task.parentTaskId?.let { into.put("parentTaskId", it) }
            into.put("tagIds", kind.task.tagIds.joinToString(LIST_SEPARATOR))
            kind.task.dueDate?.let { into.put("dueDate", it) }
            kind.task.dueTime?.let { into.put("dueTime", it) }
            into.put("someday", kind.task.someday)
        }

        is ProposalItemKind.CreateProject -> {
            into.put("name", kind.project.name)
            kind.project.description?.let { into.put("description", it) }
            into.put("color", kind.project.color)
            kind.project.icon?.let { into.put("icon", it) }
        }

        is ProposalItemKind.CreateNote -> {
            into.put("title", kind.note.title)
            kind.note.bodyHtml?.let { into.put("bodyHtml", it) }
            into.put("isFolder", kind.note.isFolder)
            kind.note.parentNoteId?.let { into.put("parentNoteId", it) }
        }

        is ProposalItemKind.ExtractActions -> into.put("actions", kind.actions.joinToString(LIST_SEPARATOR))

        else -> Unit
    }
}

/** The payload of the kinds that take something away. */
private fun writeDeletionPayload(kind: ProposalItemKind, into: JsonObjectBuilder) {
    when (kind) {
        is ProposalItemKind.DeleteNote -> kind.reason?.let { into.put("reason", it) }
        is ProposalItemKind.DeleteTask -> kind.reason?.let { into.put("reason", it) }
        is ProposalItemKind.DeleteProject -> kind.reason?.let { into.put("reason", it) }
        is ProposalItemKind.DeleteTag -> kind.reason?.let { into.put("reason", it) }
        else -> Unit
    }
}

/**
 * Separators used to pack list payloads into single text columns.
 *
 * Unit Separator and Record Separator rather than commas: a checklist item or a
 * subtask title is free text and may legitimately contain a comma.
 */
private const val LIST_SEPARATOR = "\u001F"
private const val RECORD_SEPARATOR = "\u001E"
private const val FIELD_SEPARATOR = "\u001D"

/** Reads a `JsonPrimitive` as a non-empty string, or null. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
