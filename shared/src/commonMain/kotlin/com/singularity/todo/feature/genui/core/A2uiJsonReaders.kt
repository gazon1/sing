package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Typed reads off a raw JSON object.
 *
 * They are deliberately forgiving — a number where a string was declared reads as null rather than
 * throwing — because the property check that runs alongside them is what decides whether a value is
 * acceptable. A reader that refused first would turn a reportable mistake into an exception thrown
 * from inside a stream collector, which is the one place there is nothing to catch it.
 */
internal fun JsonObject.refs(name: String): List<NodeRef> {
    val array: JsonArray = this[name] as? JsonArray ?: return emptyList()
    return array.mapNotNull { it.asStringOrNull() }.map { NodeRef(it) }
}

internal fun JsonObject.tone(): UiNode.Tone = enumOf(this["tone"], UiNode.Tone.Default)

internal fun JsonObject.optionalInt(name: String): Int? = this[name].asIntOrNull()

internal fun JsonObject.optionalBoolean(name: String): Boolean? = this[name].asBooleanOrNull()

internal fun JsonObject.direction(): UiNode.Direction =
    enumOf(this["direction"], UiNode.Direction.Vertical)

/**
 * Reads an enumerated property, falling back rather than failing.
 *
 * The catalog's `values` list is the real check — a model is told the legal values when it gets
 * one wrong. This only has to keep an unknown name from becoming an exception on the way there.
 */
internal inline fun <reified T : Enum<T>> enumOf(element: JsonElement?, fallback: T): T {
    val name: String = element.asStringOrNull() ?: return fallback
    return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}

internal fun JsonElement?.asStringOrNull(): String? {
    val primitive: JsonPrimitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
