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
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.proposals.domain.model.stringOrNull
import com.singularity.todo.feature.proposals.domain.model.toJsonObject
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
    taskId = TaskId(taskId),
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
    when (obj.stringOrNull("type")) {
        "SetTaskField" -> ProposalItemKind.SetTaskField(
            field = TaskField.valueOf(obj.requireString("field")),
            value = obj.stringOrNull("value").orEmpty(),
        )

        "AddTags" -> ProposalItemKind.AddTags(obj.list("names"))

        "RemoveTags" -> ProposalItemKind.RemoveTags(obj.list("tagIds"))

        "AddChecklistItems" -> ProposalItemKind.AddChecklistItems(obj.list("texts"))

        "AddSubtasks" -> ProposalItemKind.AddSubtasks(obj.list("titles"))

        "AddTimeEntries" -> ProposalItemKind.AddTimeEntries(
            obj.stringOrNull("entries")
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
                },
        )

        else -> return null
    }
}.getOrNull()

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
