package com.singularity.todo.feature.proposals.data

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.NoteField
import com.singularity.todo.feature.proposals.domain.model.ProjectField
import com.singularity.todo.feature.proposals.domain.model.ProposedNote
import com.singularity.todo.feature.proposals.domain.model.ProposedProject
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.proposals.domain.model.stringOrNull
import com.singularity.todo.feature.proposals.domain.model.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

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

/** Converts a persisted proposal row to the domain model. */
internal fun AiProposalEntity.toDomain(items: List<ProposalItem> = emptyList()): AiProposal = AiProposal(
    id = ProposalId(id),
    targetKind = targetKind,
    targetId = targetId,
    userId = UserId(userId),
    source = enumOrDefault(source, ProposalSource.Detail),
    status = enumOrDefault(status, ProposalStatus.Pending),
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    items = items,
)

/**
 * Converts a persisted item row to the domain model.
 *
 * A `kind_json` blob that fails to parse yields `null` rather than throwing: one
 * corrupt row must not make the whole proposal card unrenderable, and the item is
 * already decided-or-pending state that the user can still act on via the rest of
 * the card. The caller filters nulls out.
 */
internal fun ProposalItemEntity.toDomainOrNull(): ProposalItem? {
    val kind = decodeKind(kindJson) ?: return null
    return ProposalItem(
        id = ProposalItemId(id),
        proposalId = ProposalId(proposalId),
        kind = kind,
        targetId = targetId,
        humanSummary = humanSummary,
        status = enumOrDefault(status, ProposalItemStatus.Pending),
        fingerprint = fingerprint,
        sortOrder = sortOrder,
        decidedAt = decidedAt?.let(Instant::fromEpochMilliseconds),
        decidedActor = decidedActor?.let { runCatching { DecidedActor.valueOf(it) }.getOrNull() },
        rejectionReason = rejectionReason,
    )
}

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
 * Split by group rather than one long `when`: a single `when` over the whole
 * vocabulary is a function nobody can read, and the compiler stops pointing at
 * the place a new kind has to be taught.
 */
private fun decodeTaskKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "SetTaskField" -> ProposalItemKind.SetTaskField(
        field = TaskField.valueOf(obj.requireString("field")),
        value = obj.stringOrNull("value").orEmpty(),
    )

    "SetTaskTags" -> ProposalItemKind.SetTaskTags(obj.list("tagIds"))

    "AddTags" -> ProposalItemKind.AddTags(obj.list("names"))

    "RemoveTags" -> ProposalItemKind.RemoveTags(obj.list("tagIds"))

    "AddChecklistItems" -> ProposalItemKind.AddChecklistItems(obj.list("texts"))

    "AddSubtasks" -> ProposalItemKind.AddSubtasks(obj.list("titles"))

    "AddTimeEntries" -> ProposalItemKind.AddTimeEntries(decodeTimeEntries(obj))

    else -> null
}

/** The kinds a note or a project carries. */
private fun decodeEntityKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "SetNoteField" -> ProposalItemKind.SetNoteField(
        field = NoteField.valueOf(obj.requireString("field")),
        value = obj.stringOrNull("value").orEmpty(),
    )

    "SetProjectField" -> ProposalItemKind.SetProjectField(
        field = ProjectField.valueOf(obj.requireString("field")),
        value = obj.stringOrNull("value").orEmpty(),
    )

    else -> null
}

/** The kinds that bring something into being. */
private fun decodeCreationKind(obj: JsonObject): ProposalItemKind? = when (obj.stringOrNull("type")) {
    "CreateTask" -> ProposalItemKind.CreateTask(decodeProposedTask(obj))
    "CreateProject" -> ProposalItemKind.CreateProject(decodeProposedProject(obj))
    "CreateNote" -> ProposalItemKind.CreateNote(decodeProposedNote(obj))
    "ExtractActions" -> ProposalItemKind.ExtractActions(obj.list("actions"))
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
    priority = obj.stringOrNull("priority") ?: "None",
    kind = obj.stringOrNull("kind") ?: "Task",
    projectId = obj.stringOrNull("projectId"),
    parentTaskId = obj.stringOrNull("parentTaskId"),
    tagIds = obj.list("tagIds"),
    dueDate = obj.stringOrNull("dueDate"),
    dueTime = obj.stringOrNull("dueTime"),
    someday = obj.stringOrNull("someday") == "true",
)

/** The project a `CreateProject` item brings into being. */
private fun decodeProposedProject(obj: JsonObject): ProposedProject = ProposedProject(
    name = obj.stringOrNull("name").orEmpty(),
    description = obj.stringOrNull("description"),
    color = obj.stringOrNull("color")?.toIntOrNull() ?: 0xFF2196F3.toInt(),
    icon = obj.stringOrNull("icon"),
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
 * Parses an enum name from the database, falling back to [fallback].
 *
 * A row written by a build that knew a status we do not (or vice versa) should render
 * as something sensible rather than crash the card.
 */
private inline fun <reified E : Enum<E>> enumOrDefault(raw: String, fallback: E): E =
    runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)

/** Splits a packed list column, dropping blanks. */
private fun JsonObject.list(key: String): List<String> = stringOrNull(key).orEmpty()
    .split(ProposalPayloadSep.LIST)
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/** Reads a required key, throwing when absent so [decodeKind] returns null. */
private fun JsonObject.requireString(key: String): String = stringOrNull(key) ?: error("missing '$key'")
