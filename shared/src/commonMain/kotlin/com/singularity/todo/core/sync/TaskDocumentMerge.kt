package com.singularity.todo.core.sync

import com.singularity.todo.feature.tasks.domain.model.BLOCKED_BY_WIRE_NAME
import com.singularity.todo.feature.tasks.domain.model.TAGS_WIRE_NAME
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.serialization.json.JsonObject

/**
 * The keys of the parts of a task document that live outside the row.
 *
 * The tags and the blocking edges are not columns of the row — they live in
 * cross-reference tables, and their claims are their own business. So the
 * local row does not speak about them here: the merge leaves those keys to the
 * document alone, and a document that carries no `tags` key is still a
 * document that carries no `tags` key afterwards.
 *
 * The row itself is a different matter — see
 * `docs/decisions/2026-10-23-the-row-keeps-what-the-document-does-not-say.md`.
 */
private val PARTS_OUTSIDE_THE_ROW = setOf(TAGS_WIRE_NAME, BLOCKED_BY_WIRE_NAME)

/**
 * The document with the local row's claims for the fields the document does not
 * carry.
 *
 * The document wins wherever it speaks. Where it says nothing, the row that is
 * already here keeps saying what it says: a field the document does not carry
 * is not a claim that the field is empty, and an empty field is a deletion.
 *
 * @param document the document that arrived — it wins wherever it speaks.
 * @param local the row that is already on this device, or `null` when there is
 *   none (a document about a task this device has never seen).
 */
internal fun mergeWithLocalRow(document: JsonObject, local: Task?): JsonObject {
    if (local == null) return document
    val localRow = local.toJson().filterKeys { it !in PARTS_OUTSIDE_THE_ROW }
    return JsonObject(localRow + document)
}
