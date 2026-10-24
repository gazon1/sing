package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * The document with the local row's claims for the fields the document does not
 * carry.
 *
 * The document wins wherever it speaks. Where it says nothing, the row that is
 * already here keeps saying what it says: a field the document does not carry
 * is not a claim that the field is empty, and an empty field is a deletion.
 *
 * The row is given as the JSON the model writes — the same names the document
 * uses, so that the two can be compared key by key, and so that a rename of a
 * wire field cannot leave this check behind. A row that is `null` is a
 * document about a row this device has never seen, and the document is
 * written as it is.
 *
 * The rule is the same for every type that is synced. What differs from type
 * to type is which keys are *parts* — the fields that live outside the row and
 * are therefore not the row's to speak about — and that is the caller's
 * knowledge, not this function's: it filters the row before it arrives here.
 *
 * @param document the document that arrived — it wins wherever it speaks.
 * @param localRow the row that is already on this device, as the model writes
 *   it, or `null` when there is none.
 *
 * See `docs/decisions/2026-10-23-the-row-keeps-what-the-document-does-not-say.md`
 * and `docs/decisions/2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type.md`.
 */
internal fun mergeWithLocalRow(document: JsonObject, localRow: JsonObject?): JsonObject {
    if (localRow == null) return document
    return JsonObject(localRow + document)
}
