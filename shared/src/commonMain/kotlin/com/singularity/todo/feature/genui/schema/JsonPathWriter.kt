package com.singularity.todo.feature.genui.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Writing a value into a document at a path, creating whatever the path implies on the way.
 *
 * Separate from [DataModel] because writing is the half with rules in it. Reading is a walk down an
 * existing structure and cannot fail in an interesting way; writing has to decide what kind of node
 * each missing segment becomes, and getting that wrong is the quietest failure available — the write
 * succeeds, the surface renders, and reading the path back yields nothing.
 *
 * The rule that matters: a numeric segment creates an **array**. Writing `/tasks/0/title` into an
 * object with a `"0"` key looks identical from the outside, and every list-shaped path a task card
 * binds is affected — which is to say every task list the app asks for.
 */
internal object JsonPathWriter {

    /** Writes [value] at [segments] (root-to-leaf) into [doc], returning the new document. */
    fun write(doc: JsonObject, segments: List<String>, value: JsonElement): JsonObject =
        writeInto(doc, segments, value) as? JsonObject ?: JsonObject(emptyMap())

    private fun writeInto(container: JsonElement, segments: List<String>, value: JsonElement): JsonElement {
        if (segments.isEmpty()) return value
        val head: String = segments.first()
        val rest: List<String> = segments.drop(1)
        return when (container) {
            is JsonArray -> writeIntoArray(container, head.toIntOrNull(), rest, value)
            else -> writeIntoObject(container as? JsonObject ?: JsonObject(emptyMap()), head, rest, value)
        }
    }

    private fun writeIntoObject(
        doc: JsonObject,
        key: String,
        rest: List<String>,
        value: JsonElement,
    ): JsonObject {
        val existing: JsonElement = doc[key] ?: JsonObject(emptyMap())
        val updated: JsonElement = if (rest.isEmpty()) {
            value
        } else {
            // A numeric key with something after it means an array, whatever was there before.
            val container: JsonElement = if (rest.first().isIndex() && existing !is JsonArray) {
                JsonArray(emptyList())
            } else {
                existing
            }
            writeInto(container, rest, value)
        }
        return JsonObject(doc.toMutableMap().apply { this[key] = updated })
    }

    /**
     * Grows the array to reach [index] before writing into it.
     *
     * The gap is filled with nulls rather than shifted over: an index is a position, and moving
     * everything below it would change the meaning of values already written.
     */
    private fun writeIntoArray(
        array: JsonArray,
        index: Int?,
        rest: List<String>,
        value: JsonElement,
    ): JsonElement {
        if (index == null) return JsonArray(array)
        val list: MutableList<JsonElement> = array.toMutableList()
        while (list.size <= index) list.add(JsonNull)
        val existing: JsonElement = list[index]
        list[index] = when {
            rest.isEmpty() -> value
            existing is JsonNull -> writeInto(emptyFor(rest.first()), rest, value)
            else -> writeInto(existing, rest, value)
        }
        return JsonArray(list)
    }

    /** The empty node a path segment implies: an array for an index, an object for a name. */
    private fun emptyFor(segment: String): JsonElement =
        if (segment.isIndex()) JsonArray(emptyList()) else JsonObject(emptyMap())

    private fun String.isIndex(): Boolean = isNotEmpty() && all { it.isDigit() }
}
