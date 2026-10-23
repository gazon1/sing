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
import kotlinx.serialization.json.JsonPrimitive

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
 * A blob this build cannot read is not a `null`: it is a change that carries the
 * reason it cannot be read. The row was written by a build that knew a kind this
 * one does not, or the blob is not a blob at all — and a change that quietly
 * disappears from the card is a change nobody decided about. So the reader always
 * returns a kind, and the kind it returns for a blob it cannot read is
 * [ProposalItemKind.Unknown], which the card shows with its reason.
 *
 * @param raw the stored blob, exactly as it was found. It is kept inside the
 *   [ProposalItemKind.Unknown] so the row can be written back unchanged.
 */
internal fun decodeKind(raw: String): ProposalItemKind {
    val obj = runCatching { payloadJson.parseToJsonElement(raw) as JsonObject }.getOrNull()
        ?: return ProposalItemKind.Unknown(raw)
    // The sealed format first: it is the one this build writes.
    //
    // A flat blob cannot be read as a sealed one by accident: every sealed
    // variant carries a payload the flat format does not have — a nested object
    // for a value, an array for a list, a nested object for a created thing — and
    // a flat blob has a string where the sealed one has that structure. So the
    // flat blob either fails to decode as sealed, or it decodes to the very same
    // value (the deletions and the unknown kind, whose two shapes are one).
    decodeSealed(obj)?.let { return it }
    // The flat format: what the builds before this one wrote. It stays readable
    // until the oldest supported build is this one — see the ADR.
    return decodeFlat(obj, raw) ?: ProposalItemKind.Unknown(raw)
}

/**
 * The blob read as the sealed format — or null when it is not one.
 *
 * The sealed format is the model's own shape: a discriminator and the payload
 * the variant actually has. A field that is renamed or dropped in the model is a
 * field the sealed reader refuses to guess at, and a blob it refuses is a blob
 * the flat reader gets a chance at, and then [ProposalItemKind.Unknown].
 */
private fun decodeSealed(obj: JsonObject): ProposalItemKind? =
    runCatching { payloadJson.decodeFromJsonElement(ProposalItemKind.serializer(), obj) }.getOrNull()

/**
 * The blob read as the flat format — the one the builds before this one wrote.
 *
 * The flat format packs a payload into text columns and names the field
 * separately from the value, so a value is re-read through the domain's reader:
 * a priority is the string "High" here, and the domain says what that is. It
 * stays readable for as long as a build that writes it can be in the user's
 * hands.
 */
private fun decodeFlat(obj: JsonObject, raw: String): ProposalItemKind? =
    decodeUnknownKind(obj, raw)
        ?: decodeTaskKind(obj)
        ?: decodeEntityKind(obj)
        ?: decodeCreationKind(obj)
        ?: decodeDeletionKind(obj)

/**
 * The change that was written as a change this build cannot read.
 *
 * It is read first: the row says so itself, and the blob it carries is the
 * original one — the whole stored blob when the row does not name it.
 */
private fun decodeUnknownKind(obj: JsonObject, raw: String): ProposalItemKind? =
    if (obj.stringOrNull("type") == "Unknown") {
        ProposalItemKind.Unknown(obj.stringOrNull("raw") ?: raw)
    } else {
        null
    }

/**
 * The kinds a task carries: its fields, its tags, its checklist, its subtasks, its time.
 *
 * Split by group rather than one long `when`: the vocabulary grew, and a single
 * `when` over every kind is a function nobody can read — and a function nobody can
 * read is one that gets a new kind added to it wrongly.
 */
private fun decodeTaskKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "SetTaskField" -> ProposalItemKind.SetTaskField(readValue(obj, TaskValue::read, TaskValue::Unreadable))
    "SetTaskTags" -> ProposalItemKind.SetTaskTags(obj.list("tagIds"))
    "AddTags" -> ProposalItemKind.AddTags(obj.list("names"))
    "RemoveTags" -> ProposalItemKind.RemoveTags(obj.list("tagIds"))
    "AddChecklistItems" -> ProposalItemKind.AddChecklistItems(obj.list("texts"))
    "AddSubtasks" -> ProposalItemKind.AddSubtasks(obj.list("titles"))
    "AddTimeEntries" -> ProposalItemKind.AddTimeEntries(decodeTimeEntries(obj))
    "SetTagField" -> ProposalItemKind.SetTagField(readValue(obj, TagValue::read, TagValue::Unreadable))
    else -> null
}

/** The kinds a note or a project carries. */
private fun decodeEntityKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "SetNoteField" -> ProposalItemKind.SetNoteField(readValue(obj, NoteValue::read, NoteValue::Unreadable))
    "SetProjectField" -> ProposalItemKind.SetProjectField(readValue(obj, ProjectValue::read, ProjectValue::Unreadable))
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
 * is, and the domain says whether that is a value the entity can hold.
 *
 * A value that does not read is not a row to drop. The row was written by a build
 * that knew the field and this one does not, so the change becomes a value that
 * carries the reason it cannot be read — and the card shows the change with that
 * reason, instead of the change quietly disappearing from the proposal. The reason
 * belongs to the reader, so it is asked of the reader again on the way in, and the
 * row keeps the flat shape it has always had.
 */
private fun <T> readValue(
    obj: JsonObject,
    read: (String, String) -> ReadResult<T>,
    unreadable: (String, String) -> T,
): T {
    val field = obj.stringOrNull("field") ?: UNKNOWN_FIELD
    val text = obj.stringOrNull("value").orEmpty()
    return when (val result = read(field, text)) {
        is ReadResult.Ok -> result.value
        is ReadResult.Rejected -> unreadable(field, text)
    }
}

/** The field a row that does not say which field it sets is read as. */
private const val UNKNOWN_FIELD = "unknown"

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

/**
 * Serializes a kind for storage.
 *
 * The sealed format: the model's own shape, written by the model's own
 * serializer. The flat format this replaces was written by hand, field by
 * field, and a field the model renamed was a field the flat writer kept
 * writing under the old name — or stopped writing, and the value read back as
 * something else. The sealed format cannot do that: the serializer and the
 * model are the same declaration, so a field that changed is a field the
 * serializer changed with.
 */
internal fun ProposalItemKind.toJsonString(): String =
    payloadJson.encodeToString(ProposalItemKind.serializer(), this)

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
