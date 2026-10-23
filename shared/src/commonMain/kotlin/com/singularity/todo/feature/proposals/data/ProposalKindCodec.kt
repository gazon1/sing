package com.singularity.todo.feature.proposals.data

import com.singularity.todo.core.database.LocalTimeFormats
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedNote
import com.singularity.todo.feature.proposals.domain.model.ProposedProject
import com.singularity.todo.feature.proposals.domain.model.ProposedTag
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.model.TagValue
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Separators used to pack list payloads into single text columns.
 *
 * Unit Separator (U+001F) and Record Separator (U+001E) are used rather than commas
 * because a checklist item or subtask title is free text and may legitimately contain
 * a comma — splitting on it would corrupt the data, and joining on it would be
 * ambiguous on the way back in.
 */
internal object ProposalPayloadSep {
    const val LIST = "\u001F"
    const val RECORD = "\u001E"
    const val FIELD = "\u001D"
}

/** JSON codec used for the `kind_json` column. */
private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * Decodes the `kind_json` blob back into a [ProposalItemKind].
 *
 * @return null if the blob is unparseable or names a type we do not know — which
 *   happens when an older build wrote a kind a newer build dropped.
 */
internal fun decodeKind(raw: String): ProposalItemKind? = runCatching {
    val obj = payloadJson.parseToJsonElement(raw) as JsonObject
    decodeTaskKind(obj) ?: decodeEntityKind(obj) ?: decodeCreationKind(obj) ?: decodeDeletionKind(obj)
}.getOrNull()

/**
 * The kinds a task carries: its fields, its tags, its checklist, its subtasks, its time.
 *
 * Split by group rather than one long `when`: the vocabulary grew, and a single
 * `when` over every kind is a function nobody can read — and a function nobody can
 * read is one that gets a new kind added to it wrongly.
 */
private fun decodeTaskKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "SetTaskField" -> readValue(obj, TaskValue::read)?.let { ProposalItemKind.SetTaskField(it) }
    "SetTaskTags" -> ProposalItemKind.SetTaskTags(obj.list("tagIds"))
    "AddTags" -> ProposalItemKind.AddTags(obj.list("names"))
    "RemoveTags" -> ProposalItemKind.RemoveTags(obj.list("tagIds"))
    "AddChecklistItems" -> ProposalItemKind.AddChecklistItems(obj.list("texts"))
    "AddSubtasks" -> ProposalItemKind.AddSubtasks(obj.list("titles"))
    "AddTimeEntries" -> ProposalItemKind.AddTimeEntries(decodeTimeEntries(obj))
    "SetTagField" -> readValue(obj, TagValue::read)?.let { ProposalItemKind.SetTagField(it) }
    else -> null
}

/** The kinds a note or a project carries. */
private fun decodeEntityKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "SetNoteField" -> readValue(obj, NoteValue::read)?.let { ProposalItemKind.SetNoteField(it) }
    "SetProjectField" -> readValue(obj, ProjectValue::read)?.let { ProposalItemKind.SetProjectField(it) }
    else -> null
}

/** The kinds that bring something into being. */
private fun decodeCreationKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "CreateTask" -> ProposalItemKind.CreateTask(decodeProposedTask(obj))
    "CreateProject" -> ProposalItemKind.CreateProject(decodeProposedProject(obj))
    "CreateNote" -> ProposalItemKind.CreateNote(decodeProposedNote(obj))
    "ExtractActions" -> ProposalItemKind.ExtractActions(obj.list("actions"))
    "CreateTag" -> ProposalItemKind.CreateTag(decodeProposedTag(obj))
    else -> null
}

/** The kinds that take something away. */
private fun decodeDeletionKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "DeleteNote" -> ProposalItemKind.DeleteNote(reason = obj.stringOrNull("reason"))
    "DeleteTask" -> ProposalItemKind.DeleteTask(reason = obj.stringOrNull("reason"))
    "DeleteProject" -> ProposalItemKind.DeleteProject(reason = obj.stringOrNull("reason"))
    "DeleteTag" -> ProposalItemKind.DeleteTag(reason = obj.stringOrNull("reason"))
    else -> null
}

/**
 * The value a row carries, read through the domain's own reader.
 *
 * One reader for every entity: the row says which field it sets and what the value
 * is, and the domain says whether that is a value the entity can hold. A row whose
 * value cannot be read is a row this build cannot show — the value is the change, so
 * there is nothing to propose — and `ProposalMapperTest` proves that every kind this
 * build knows survives the round trip.
 */
private fun <T> readValue(obj: JsonObject, read: (String, String) -> ReadResult<T>): T? {
    val field = obj.stringOrNull("field") ?: return null
    val text = obj.stringOrNull("value").orEmpty()
    return when (val result = read(field, text)) {
        is ReadResult.Ok -> result.value
        is ReadResult.Rejected -> null
    }
}

/** The packed time entries of an `AddTimeEntries` item. */
private fun decodeTimeEntries(obj: JsonObject): List<ProposedTimeEntry> = obj.stringOrNull("entries")
    .orEmpty()
    .split(ProposalPayloadSep.RECORD)
    .filter { it.isNotEmpty() }
    .mapNotNull { record ->
        val parts = record.split(ProposalPayloadSep.FIELD)
        if (parts.size < 2) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val end = parts[1].toLongOrNull() ?: return@mapNotNull null
        ProposedTimeEntry(
            startedAt = start,
            endedAt = end,
            note = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

/** The task a `CreateTask` item brings into being. */
private fun decodeProposedTask(obj: JsonObject): ProposedTask = ProposedTask(
    title = obj.stringOrNull("title").orEmpty(),
    description = obj.stringOrNull("description"),
    priority = readPriority(obj.stringOrNull("priority")),
    kind = readKind(obj.stringOrNull("kind")),
    projectId = obj.stringOrNull("projectId"),
    parentTaskId = obj.stringOrNull("parentTaskId"),
    tagIds = obj.list("tagIds"),
    dueDate = readDate(obj.stringOrNull("dueDate")),
    dueTime = readTime(obj.stringOrNull("dueTime")),
    someday = obj.stringOrNull("someday") == "true",
)

/** The project a `CreateProject` item brings into being. */
private fun decodeProposedProject(obj: JsonObject): ProposedProject = ProposedProject(
    name = obj.stringOrNull("name").orEmpty(),
    description = obj.stringOrNull("description"),
    color = obj.stringOrNull("color")?.toIntOrNull() ?: 0xFF2196F3.toInt(),
    icon = obj.stringOrNull("icon"),
)

/** The tag a `CreateTag` item brings into being. */
private fun decodeProposedTag(obj: JsonObject): ProposedTag = ProposedTag(
    name = obj.stringOrNull("name").orEmpty(),
    color = obj.stringOrNull("color")?.toIntOrNull() ?: 0xFF9E9E9E.toInt(),
)

/** The note a `CreateNote` item brings into being. */
private fun decodeProposedNote(obj: JsonObject): ProposedNote = ProposedNote(
    title = obj.stringOrNull("title").orEmpty(),
    bodyHtml = obj.stringOrNull("bodyHtml"),
    isFolder = obj.stringOrNull("isFolder") == "true",
    parentNoteId = obj.stringOrNull("parentNoteId"),
)

/** Serializes a kind for storage. */
internal fun ProposalItemKind.toJsonString(): String = toJsonObject().toString()

/**
 * Renders a [ProposalItemKind] to a compact JSON object for the `kind_json` column.
 *
 * Uses [buildJsonObject] rather than polymorphic serialization so the stored shape
 * stays flat and greppable in the database.
 */
private fun ProposalItemKind.toJsonObject(): JsonObject = buildJsonObject {
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
            into.put("field", kind.value.fieldName)
            into.put("value", kind.value.text)
        }

        is ProposalItemKind.SetTagField -> {
            into.put("field", kind.value.fieldName)
            into.put("value", kind.value.text)
        }

        is ProposalItemKind.SetTaskTags -> into.put("tagIds", kind.tagIds.joinToString(ProposalPayloadSep.LIST))

        is ProposalItemKind.AddTags -> into.put("names", kind.names.joinToString(ProposalPayloadSep.LIST))

        is ProposalItemKind.RemoveTags -> into.put("tagIds", kind.tagIds.joinToString(ProposalPayloadSep.LIST))

        is ProposalItemKind.AddChecklistItems -> into.put("texts", kind.texts.joinToString(ProposalPayloadSep.LIST))

        is ProposalItemKind.AddSubtasks -> into.put("titles", kind.titles.joinToString(ProposalPayloadSep.LIST))

        is ProposalItemKind.AddTimeEntries -> {
            into.put(
                "entries",
                kind.entries.joinToString(ProposalPayloadSep.RECORD) {
                    "${it.startedAt}${ProposalPayloadSep.FIELD}${it.endedAt}" +
                        "${ProposalPayloadSep.FIELD}${it.note.orEmpty()}"
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
            into.put("field", kind.value.fieldName)
            into.put("value", kind.value.text)
        }

        is ProposalItemKind.SetProjectField -> {
            into.put("field", kind.value.fieldName)
            into.put("value", kind.value.text)
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
            into.put("priority", kind.task.priority.name)
            into.put("kind", kind.task.kind.name)
            kind.task.projectId?.let { into.put("projectId", it) }
            kind.task.parentTaskId?.let { into.put("parentTaskId", it) }
            into.put("tagIds", kind.task.tagIds.joinToString(ProposalPayloadSep.LIST))
            kind.task.dueDate?.let { into.put("dueDate", it.toString()) }
            kind.task.dueTime?.let { into.put("dueTime", LocalTimeFormats.format(it)) }
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

        is ProposalItemKind.ExtractActions -> into.put("actions", kind.actions.joinToString(ProposalPayloadSep.LIST))

        is ProposalItemKind.CreateTag -> {
            into.put("name", kind.tag.name)
            into.put("color", kind.tag.color)
        }

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

/** Splits a packed list column, dropping blanks. */
private fun JsonObject.list(key: String): List<String> = stringOrNull(key).orEmpty()
    .split(ProposalPayloadSep.LIST)
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/** Reads a `JsonPrimitive` as a non-empty string, or null. */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

/** The priority a row carries, read in any of the spellings the model writes. */
private fun readPriority(raw: String?): TaskPriority {
    val text = raw?.trim().orEmpty()
    return TaskPriority.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: TaskPriority.None
}

/** The kind of work a row carries, read in any of the spellings the model writes. */
private fun readKind(raw: String?): TaskKind {
    val text = raw?.trim().orEmpty()
    return TaskKind.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: TaskKind.Task
}

/** The date a row carries, or null when the row carries none. */
private fun readDate(raw: String?): LocalDate? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** The time a row carries, or null when the row carries none. */
private fun readTime(raw: String?): LocalTime? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalTimeFormats.parse(it) }.getOrNull() }
