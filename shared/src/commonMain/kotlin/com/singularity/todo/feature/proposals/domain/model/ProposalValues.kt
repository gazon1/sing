package com.singularity.todo.feature.proposals.domain.model

import com.singularity.todo.core.database.LocalTimeFormats
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The outcome of reading a value the model produced.
 *
 * A value is either read or it is not, and the difference matters to two different
 * audiences: the model can fix a value it named wrong, the user cannot. So the
 * reading says which it is — a value, or the reason the value could not be read.
 */
sealed interface ReadResult<out T> {

    /** The value, read. */
    data class Ok<T>(val value: T) : ReadResult<T>

    /** The value could not be read, and this is why. */
    data class Rejected(val reason: String) : ReadResult<Nothing>
}

/**
 * A value the user may change before the change is applied.
 *
 * @property text The value as it reads now — what the field is filled with.
 * @property change The same value with [text] replaced — or why it cannot be.
 */
class EditableValue<T>(val text: String, val change: (String) -> ReadResult<T>)

/**
 * What every value in the proposal dictionary has in common.
 *
 * The four dictionaries — task, note, project, tag — grew side by side, and each
 * of them repeated the same three members and the same helper. A new field was
 * therefore written four times over, and a new dictionary would have had to
 * remember all of it: the repetition is where the next value gets added to one
 * dictionary and forgotten in another.
 */
sealed interface ProposalValue {

    /** The name of the field this value sets — the name the stored payload uses. */
    val fieldName: String

    /** What the user reads on the card. */
    val summary: String

    /** The value as it is stored. */
    val text: String

    /**
     * Why this value cannot be read, when it cannot.
     *
     * A value that does not read is not dropped: the change stays on the card
     * with the reason, so the user learns what happened instead of finding that
     * a change has quietly gone missing.
     */
    val readFailure: String?
        get() = null
}

/**
 * A value for a task field.
 *
 * The variant *is* the field: a title is a [Title], a priority is a [Priority].
 *
 * Before, the field and the value were two separate things — `SetTaskField(Title, "42")`
 * — so the shape of the value was whatever the model happened to type, and the code
 * that read it had to guess. A priority was the string "high" in one place and the
 * enum `High` in another, and the two spellings did not always agree. Now the value
 * carries its own type, and a proposal cannot hold a priority that is not a priority.
 */
@Serializable
sealed interface TaskValue : ProposalValue {

    /**
     * The value the user may change before the change is applied.
     *
     * Null when there is nothing here a person would type: a project is named by an
     * id the app resolves, and an id is not something a user can retype.
     */
    fun editableValue(): EditableValue<TaskValue>?

    @Serializable
    @SerialName("Title")
    data class Title(val value: String) : TaskValue {
        override val fieldName: String = "Title"
        override val summary: String = "Set the title to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<TaskValue> = editableOf("Title", TaskValue::read)
    }

    @Serializable
    @SerialName("Description")
    data class Description(val value: String) : TaskValue {
        override val fieldName: String = "Description"
        override val summary: String = "Set the description to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<TaskValue> = editableOf("Description", TaskValue::read)
    }

    @Serializable
    @SerialName("Priority")
    data class Priority(val value: TaskPriority) : TaskValue {
        override val fieldName: String = "Priority"
        override val summary: String = "Set the priority to ${value.name}"
        override val text: String = value.name
        override fun editableValue(): EditableValue<TaskValue> = editableOf("Priority", TaskValue::read)
    }

    /**
     * The due date — or nothing, which clears it.
     *
     * The value is nullable because "no due date" is a value the task can hold: a task
     * with a due date can lose it again, and an empty string has always meant that in
     * the tools that set the date.
     */
    @Serializable
    @SerialName("DueDate")
    data class DueDate(val value: LocalDate?) : TaskValue {
        override val fieldName: String = "DueDate"
        override val summary: String
            get() = if (value == null) "Clear the due date" else "Set the due date to $value"
        override val text: String = value?.toString().orEmpty()
        override fun editableValue(): EditableValue<TaskValue> = editableOf("DueDate", TaskValue::read)
    }

    /** The due time — or nothing, which clears it. The twin of [DueDate]. */
    @Serializable
    @SerialName("DueTime")
    data class DueTime(val value: LocalTime?) : TaskValue {
        override val fieldName: String = "DueTime"
        override val summary: String
            get() = if (value == null) {
                "Clear the due time"
            } else {
                "Set the due time to ${LocalTimeFormats.format(value)}"
            }
        override val text: String = value?.let { LocalTimeFormats.format(it) }.orEmpty()
        override fun editableValue(): EditableValue<TaskValue> = editableOf("DueTime", TaskValue::read)
    }

    @Serializable
    @SerialName("Status")
    data class Status(val value: TaskStatus) : TaskValue {
        override val fieldName: String = "Status"
        override val summary: String = "Set the status to ${value.name}"
        override val text: String = value.name
        override fun editableValue(): EditableValue<TaskValue> = editableOf("Status", TaskValue::read)
    }

    @Serializable
    @SerialName("EstimateMinutes")
    data class EstimateMinutes(val value: Int) : TaskValue {
        override val fieldName: String = "EstimateMinutes"
        override val summary: String = "Set the estimate to $value min"
        override val text: String = value.toString()
        override fun editableValue(): EditableValue<TaskValue> = editableOf("EstimateMinutes", TaskValue::read)
    }

    @Serializable
    @SerialName("Kind")
    data class Kind(val value: TaskKind) : TaskValue {
        override val fieldName: String = "Kind"
        override val summary: String = "Set the kind of work to ${value.name}"
        override val text: String = value.name
        override fun editableValue(): EditableValue<TaskValue> = editableOf("Kind", TaskValue::read)
    }

    /** The project the task belongs to, as a project id. An empty value moves it out of every project. */
    @Serializable
    @SerialName("Project")
    data class Project(val value: String) : TaskValue {
        override val fieldName: String = "Project"
        override val summary: String = "Change the project"
        override val text: String = value

        /**
         * Not editable.
         *
         * The value is a project id, not a name: the id is what the app resolves the
         * name to, and a field the user types into would have to be a name — which is
         * a different value, resolved at a different moment. Offering the edit would
         * be offering a field that cannot be filled in correctly.
         */
        override fun editableValue(): EditableValue<TaskValue>? = null
    }

    /** Whether the task is deferred. */
    @Serializable
    @SerialName("Someday")
    data class Someday(val value: Boolean) : TaskValue {
        override val fieldName: String = "Someday"
        override val summary: String
            get() = if (value) "Defer the task" else "Bring the task back"
        override val text: String = value.toString()
        override fun editableValue(): EditableValue<TaskValue> = editableOf("Someday", TaskValue::read)
    }

    /**
     * A value this build cannot read.
     *
     * The row was written by a build that knew the field, and this one does not:
     * an enum lost a variant, or the spelling changed. The change is still a
     * change — it stays on the card with the reason it cannot be read, and the
     * user can type a value that does read — so it does not disappear the way a
     * row the codec could not make sense of at all would.
     *
     * The reason is not stored: it belongs to the reader, and the reader is the
     * one place that knows why a value does not read. So it is asked for again
     * here, and the value itself stays the field and the text — which is what
     * the row holds.
     */
    @Serializable
    @SerialName("Unreadable")
    data class Unreadable(val field: String, val raw: String) : TaskValue {
        override val fieldName: String = field
        override val summary: String = "Set the $field to «${raw.asCardValue()}»"
        override val text: String = raw
        override val readFailure: String?
            get() = (TaskValue.read(this.field, raw) as? ReadResult.Rejected)?.reason
        override fun editableValue(): EditableValue<TaskValue> = editableOf(field, TaskValue::read)
    }

    companion object {

        /**
         * The value [field] takes when read from [text] — or why it cannot be read.
         *
         * The one place a task field's value is read. The tools read it before they
         * store the proposal, the planner reads the stored value, and the user reads
         * it again when they change it — so a value the model named wrong is refused
         * where the model can still fix it.
         */
        fun read(field: String, text: String): ReadResult<TaskValue> = when (field.trim()) {
            "Title" -> ReadResult.Ok(Title(text))
            "Description" -> ReadResult.Ok(Description(text))
            "Priority" -> readPriority(text).map { Priority(it) }
            "DueDate" -> readDate(text).map { DueDate(it) }
            "DueTime" -> readTime(text).map { DueTime(it) }
            "Status" -> readStatus(text).map { Status(it) }
            "EstimateMinutes" -> readEstimate(text).map { EstimateMinutes(it) }
            "Kind" -> readKind(text).map { Kind(it) }
            "Project" -> ReadResult.Ok(Project(text.trim()))
            "Someday" -> readSomeday(text).map { Someday(it) }
            else -> ReadResult.Rejected("\"${field.trim()}\" is not a task field.")
        }

        /** The priority [text] names, or why it does not read. */
        fun readPriority(text: String): ReadResult<TaskPriority> {
            val trimmed = text.trim()
            val priority = TaskPriority.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: return ReadResult.Rejected("\"$trimmed\" is not a priority. $PRIORITY_NAMES")
            return ReadResult.Ok(priority)
        }

        /** The kind of work [text] names, or why it does not read. */
        fun readKind(text: String): ReadResult<TaskKind> {
            val trimmed = text.trim()
            val kind = TaskKind.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: return ReadResult.Rejected("\"$trimmed\" is not a kind of work. $KIND_NAMES")
            return ReadResult.Ok(kind)
        }

        /**
         * The date [text] names — or nothing, which clears it — or why it does not read.
         *
         * An empty string means "no due date": that is what the tools that set the date
         * have always said, and a task that can be given a date must be able to lose it.
         */
        fun readDate(text: String): ReadResult<LocalDate?> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ReadResult.Ok(null)
            val date = runCatching { LocalDate.parse(trimmed) }.getOrNull()
                ?: return ReadResult.Rejected("\"$trimmed\" is not a date. Use YYYY-MM-DD, or empty to clear it.")
            return ReadResult.Ok(date)
        }

        /**
         * The time [text] names — or nothing, which clears it — or why it does not read.
         */
        fun readTime(text: String): ReadResult<LocalTime?> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ReadResult.Ok(null)
            val time = runCatching { LocalTimeFormats.parse(trimmed) }.getOrNull()
                ?: return ReadResult.Rejected("\"$trimmed\" is not a time. Use HH:mm, or empty to clear it.")
            return ReadResult.Ok(time)
        }
    }
}

/**
 * A value for a note field.
 *
 * The note's fields are all text, but the same rule as the task's fields holds: the
 * variant is the field, so "the body" cannot be confused with "the title".
 */
@Serializable
sealed interface NoteValue : ProposalValue {

    /** The value the user may change before the change is applied. */
    fun editableValue(): EditableValue<NoteValue>?

    @Serializable
    @SerialName("Title")
    data class Title(val value: String) : NoteValue {
        override val fieldName: String = "Title"
        override val summary: String = "Set the note title to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<NoteValue> = editableOf("Title", NoteValue::read)
    }

    /** The note's body, as canonical HTML — the form the notes are stored in. */
    @Serializable
    @SerialName("Body")
    data class Body(val value: String) : NoteValue {
        override val fieldName: String = "Body"
        override val summary: String = "Set the note body to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<NoteValue> = editableOf("Body", NoteValue::read)
    }

    /** A summary of the note's content, written to the body as HTML. */
    @Serializable
    @SerialName("Summary")
    data class Summary(val value: String) : NoteValue {
        override val fieldName: String = "Summary"
        override val summary: String = "Summarize the note to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<NoteValue> = editableOf("Summary", NoteValue::read)
    }

    /**
     * A value this build cannot read.
     *
     * The row was written by a build that knew the field, and this one does not:
     * an enum lost a variant, or the spelling changed. The change is still a
     * change — it stays on the card with the reason it cannot be read, and the
     * user can type a value that does read — so it does not disappear the way a
     * row the codec could not make sense of at all would.
     *
     * The reason is not stored: it belongs to the reader, and the reader is the
     * one place that knows why a value does not read. So it is asked for again
     * here, and the value itself stays the field and the text — which is what
     * the row holds.
     */
    @Serializable
    @SerialName("Unreadable")
    data class Unreadable(val field: String, val raw: String) : NoteValue {
        override val fieldName: String = field
        override val summary: String = "Set the $field to «${raw.asCardValue()}»"
        override val text: String = raw
        override val readFailure: String?
            get() = (NoteValue.read(this.field, raw) as? ReadResult.Rejected)?.reason
        override fun editableValue(): EditableValue<NoteValue> = editableOf(field, NoteValue::read)
    }

    companion object {

        /** The value [field] takes when read from [text] — or why it cannot be read. */
        fun read(field: String, text: String): ReadResult<NoteValue> = when (field.trim()) {
            "Title" -> ReadResult.Ok(Title(text))
            "Body" -> ReadResult.Ok(Body(text))
            "Summary" -> ReadResult.Ok(Summary(text))
            else -> ReadResult.Rejected("\"${field.trim()}\" is not a note field.")
        }
    }
}

/**
 * A value for a project field.
 *
 * A project had no shape of its own in the proposal vocabulary until the write tools
 * were put on the proposal path: a task and a note could be changed, and a project
 * could only be deleted. So "change the project's name" was impossible not because
 * anybody forgot, but because there was no kind for it.
 */
@Serializable
sealed interface ProjectValue : ProposalValue {

    /** The value the user may change before the change is applied. */
    fun editableValue(): EditableValue<ProjectValue>?

    @Serializable
    @SerialName("Name")
    data class Name(val value: String) : ProjectValue {
        override val fieldName: String = "Name"
        override val summary: String = "Rename the project to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<ProjectValue> = editableOf("Name", ProjectValue::read)
    }

    @Serializable
    @SerialName("Description")
    data class Description(val value: String) : ProjectValue {
        override val fieldName: String = "Description"
        override val summary: String = "Set the project description to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<ProjectValue> = editableOf("Description", ProjectValue::read)
    }

    /** The project's colour, as an ARGB integer. */
    @Serializable
    @SerialName("Color")
    data class Color(val value: Int) : ProjectValue {
        override val fieldName: String = "Color"
        override val summary: String = "Recolor the project"
        override val text: String = value.toString()

        /** Not editable: a colour is picked, not typed. */
        override fun editableValue(): EditableValue<ProjectValue>? = null
    }

    /** The project's icon name. */
    @Serializable
    @SerialName("Icon")
    data class Icon(val value: String) : ProjectValue {
        override val fieldName: String = "Icon"
        override val summary: String = "Change the project icon"
        override val text: String = value

        /** Not editable: an icon is picked, not typed. */
        override fun editableValue(): EditableValue<ProjectValue>? = null
    }

    /**
     * A value this build cannot read.
     *
     * The row was written by a build that knew the field, and this one does not:
     * an enum lost a variant, or the spelling changed. The change is still a
     * change — it stays on the card with the reason it cannot be read, and the
     * user can type a value that does read — so it does not disappear the way a
     * row the codec could not make sense of at all would.
     *
     * The reason is not stored: it belongs to the reader, and the reader is the
     * one place that knows why a value does not read. So it is asked for again
     * here, and the value itself stays the field and the text — which is what
     * the row holds.
     */
    @Serializable
    @SerialName("Unreadable")
    data class Unreadable(val field: String, val raw: String) : ProjectValue {
        override val fieldName: String = field
        override val summary: String = "Set the $field to «${raw.asCardValue()}»"
        override val text: String = raw
        override val readFailure: String?
            get() = (ProjectValue.read(this.field, raw) as? ReadResult.Rejected)?.reason
        override fun editableValue(): EditableValue<ProjectValue> = editableOf(field, ProjectValue::read)
    }

    companion object {

        /** The value [field] takes when read from [text] — or why it cannot be read. */
        fun read(field: String, text: String): ReadResult<ProjectValue> = when (field.trim()) {
            "Name" -> ReadResult.Ok(Name(text))

            "Description" -> ReadResult.Ok(Description(text))

            "Color" -> text.trim().toIntOrNull()?.let { ReadResult.Ok(Color(it)) }
                ?: ReadResult.Rejected("\"${text.trim()}\" is not a colour. Use an ARGB number.")

            "Icon" -> ReadResult.Ok(Icon(text.trim()))

            else -> ReadResult.Rejected("\"${field.trim()}\" is not a project field.")
        }
    }
}

/**
 * A value for a tag field.
 *
 * A tag had no shape of its own in the proposal vocabulary: it could be deleted and
 * nothing else. So "rename the tag" was impossible not because anybody forgot, but
 * because there was no kind for it — and a tool that creates a tag had no way to say
 * "and here is the tag I would like to exist".
 */
@Serializable
sealed interface TagValue : ProposalValue {

    /** The value the user may change before the change is applied. */
    fun editableValue(): EditableValue<TagValue>?

    @Serializable
    @SerialName("Name")
    data class Name(val value: String) : TagValue {
        override val fieldName: String = "Name"
        override val summary: String = "Rename the tag to «${value.asCardValue()}»"
        override val text: String = value
        override fun editableValue(): EditableValue<TagValue> = editableOf("Name", TagValue::read)
    }

    /** The tag's colour, as an ARGB integer. */
    @Serializable
    @SerialName("Color")
    data class Color(val value: Int) : TagValue {
        override val fieldName: String = "Color"
        override val summary: String = "Recolor the tag"
        override val text: String = value.toString()

        /** Not editable: a colour is picked, not typed. */
        override fun editableValue(): EditableValue<TagValue>? = null
    }

    /**
     * A value this build cannot read.
     *
     * The row was written by a build that knew the field, and this one does not:
     * an enum lost a variant, or the spelling changed. The change is still a
     * change — it stays on the card with the reason it cannot be read, and the
     * user can type a value that does read — so it does not disappear the way a
     * row the codec could not make sense of at all would.
     *
     * The reason is not stored: it belongs to the reader, and the reader is the
     * one place that knows why a value does not read. So it is asked for again
     * here, and the value itself stays the field and the text — which is what
     * the row holds.
     */
    @Serializable
    @SerialName("Unreadable")
    data class Unreadable(val field: String, val raw: String) : TagValue {
        override val fieldName: String = field
        override val summary: String = "Set the $field to «${raw.asCardValue()}»"
        override val text: String = raw
        override val readFailure: String?
            get() = (TagValue.read(this.field, raw) as? ReadResult.Rejected)?.reason
        override fun editableValue(): EditableValue<TagValue> = editableOf(field, TagValue::read)
    }

    companion object {

        /** The value [field] takes when read from [text] — or why it cannot be read. */
        fun read(field: String, text: String): ReadResult<TagValue> = when (field.trim()) {
            "Name" -> ReadResult.Ok(Name(text))

            "Color" -> text.trim().toIntOrNull()?.let { ReadResult.Ok(Color(it)) }
                ?: ReadResult.Rejected("\"${text.trim()}\" is not a colour. Use an ARGB number.")

            else -> ReadResult.Rejected("\"${field.trim()}\" is not a tag field.")
        }
    }
}

/**
 * The tag a [ProposalItemKind.CreateTag] item proposes to bring into being.
 */
@Serializable
data class ProposedTag(val name: String, val color: Int = 0xFF9E9E9E.toInt())

/**
 * A time range proposed by [ProposalItemKind.AddTimeEntries].
 *
 * @param startedAt Epoch millis when the work started.
 * @param endedAt Epoch millis when it ended. Always after [startedAt].
 */
@Serializable
data class ProposedTimeEntry(val startedAt: Long, val endedAt: Long, val note: String? = null)

/**
 * The task a [ProposalItemKind.CreateTask] item proposes to bring into being.
 *
 * The values are typed: a priority is a [TaskPriority], a date is a [LocalDate]. The
 * model produces text, the tool reads it, and what the proposal holds is what the
 * task will hold — so a task cannot be created with a date that does not exist.
 */
@Serializable
data class ProposedTask(
    val title: String,
    val description: String? = null,
    val priority: TaskPriority = TaskPriority.None,
    val kind: TaskKind = TaskKind.Task,
    val projectId: String? = null,
    val parentTaskId: String? = null,
    val tagIds: List<String> = emptyList(),
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
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

/** The names a task's priority can be written with, as the model writes them. */
internal const val PRIORITY_NAMES = "Use none, low, medium, high or urgent."

/** The names a kind of work can be written with, as the model writes them. */
internal const val KIND_NAMES = "Use task or note."

/** The names a status can be written with, as the model writes them. */
internal const val STATUS_NAMES = "Use active or completed."

/**
 * The value as the card reads it: the whole thing, or the beginning of it.
 *
 * A card is a line, and a line that carries a whole note body is a line nobody
 * reads. The truncation keeps the beginning — the part that tells the user what
 * the change is about.
 */
internal fun String.asCardValue(limit: Int = 48): String =
    if (length <= limit) this else take(limit).trimEnd() + "…"

/**
 * The value the user may change, read through the dictionary it belongs to.
 *
 * One helper for the four dictionaries: a value is edited by reading the new
 * text through the reader of the entity the value belongs to, and the four
 * helpers said the same thing four times — so a new dictionary would have had
 * to remember to write a fifth.
 */
private fun <V : ProposalValue> V.editableOf(
    field: String,
    read: (String, String) -> ReadResult<V>,
): EditableValue<V> = EditableValue(text) { newText -> read(field, newText) }

private fun readStatus(text: String): ReadResult<TaskStatus> {
    val trimmed = text.trim()
    val status = TaskStatus.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        ?: return ReadResult.Rejected("\"$trimmed\" is not a status. $STATUS_NAMES")
    return ReadResult.Ok(status)
}

private fun readSomeday(text: String): ReadResult<Boolean> = when (text.trim().lowercase()) {
    "true" -> ReadResult.Ok(true)
    "false" -> ReadResult.Ok(false)
    else -> ReadResult.Rejected("\"${text.trim()}\" is not true or false.")
}

private fun readEstimate(text: String): ReadResult<Int> {
    val minutes = text.trim().toIntOrNull()
        ?: return ReadResult.Rejected("\"${text.trim()}\" is not a number of minutes.")
    if (minutes < 0) {
        return ReadResult.Rejected(
            "\"$minutes\" cannot be negative. Use 0 or a positive number of minutes.",
        )
    }
    return ReadResult.Ok(minutes)
}

/**
 * Turns a read value into another read value.
 *
 * A rejected value stays rejected: the reason is the same reason, whatever it is
 * mapped to.
 */
internal inline fun <T, R> ReadResult<T>.map(transform: (T) -> R): ReadResult<R> = when (this) {
    is ReadResult.Ok -> ReadResult.Ok(transform(value))
    is ReadResult.Rejected -> this
}
