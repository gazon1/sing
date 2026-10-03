package com.singularity.todo.feature.proposals.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
 * A time range proposed by [ProposalItemKind.AddTimeEntries].
 *
 * @param startedAt Epoch millis when the work started.
 * @param endedAt Epoch millis when it ended. Always after [startedAt].
 * @param note Optional free-text note describing the work.
 */
@Serializable
data class ProposedTimeEntry(val startedAt: Long, val endedAt: Long, val note: String? = null)

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

    /** Soft-delete the owning tag. */
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
    when (this@toJsonObject) {
        is ProposalItemKind.SetTaskField -> {
            put("field", field.name)
            put("value", value)
        }

        is ProposalItemKind.AddTags -> put("names", names.joinToString("\u001F"))

        is ProposalItemKind.RemoveTags -> put("tagIds", tagIds.joinToString("\u001F"))

        is ProposalItemKind.AddChecklistItems -> put("texts", texts.joinToString("\u001F"))

        is ProposalItemKind.AddSubtasks -> put("titles", titles.joinToString("\u001F"))

        is ProposalItemKind.AddTimeEntries -> {
            put(
                "entries",
                entries.joinToString("\u001E") { "${it.startedAt}\u001D${it.endedAt}\u001D${it.note.orEmpty()}" },
            )
        }

        is ProposalItemKind.SetNoteField -> {
            put("field", field.name)
            put("value", value)
        }

        is ProposalItemKind.DeleteNote -> {
            reason?.let { put("reason", it) }
        }

        is ProposalItemKind.ExtractActions -> put("actions", actions.joinToString("\u001F"))

        is ProposalItemKind.DeleteTask -> reason?.let { put("reason", it) }

        is ProposalItemKind.DeleteProject -> reason?.let { put("reason", it) }

        is ProposalItemKind.DeleteTag -> reason?.let { put("reason", it) }
    }
}

/** Reads a `JsonPrimitive` as a non-empty string, or null. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
