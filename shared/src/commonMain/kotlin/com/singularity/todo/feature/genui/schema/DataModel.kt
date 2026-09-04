package com.singularity.todo.feature.genui.schema

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An in-memory JSON document store with reactive subscriptions per path.
 *
 * Changes to any path are immediately visible to all collectors of affected paths.
 * This is the "DataModel" in Flutter genui / A2UI terminology.
 *
 * Thread-safe via [MutableStateFlow] (which is conflated — last-write-wins).
 */
class DataModel(initial: JsonObject = JsonObject(emptyMap())) {

    private val root = MutableStateFlow(initial)

    /** Returns the value at [path], or null if not found. */
    fun get(path: UiPath): JsonElement? {
        if (path is UiPath.Root) return root.value
        return resolve(path, root.value)
    }

    /** Sets the value at [path], creating intermediate objects as needed. */
    fun set(path: UiPath, value: JsonElement) {
        if (path is UiPath.Root) {
            root.value = value as? JsonObject ?: JsonObject(emptyMap())
            return
        }
        root.value = setNode(root.value, path, value)
    }

    /** A [Flow] of the value at [path], emitting on every change. */
    fun flow(path: UiPath): Flow<JsonElement?> = root.map { doc ->
        if (path is UiPath.Root) doc else resolve(path, doc)
    }

    /** Returns a snapshot of the entire document as [JsonObject]. */
    fun snapshot(): JsonObject = root.value

    override fun toString(): String = "DataModel(${root.value})"

    // ─── Pure helpers ─────────────────────────────────────────────────────

    private fun resolve(path: UiPath, doc: JsonElement): JsonElement? {
        var current: JsonElement = doc
        var p: UiPath = path
        while (true) {
            when (p) {
                is UiPath.Root -> return current
                is UiPath.Child -> {
                    val arr = current as? JsonArray
                    current = arr?.getOrNull(p.index) ?: return null
                    p = p.tail
                }
                is UiPath.Prop -> {
                    val obj = current as? JsonObject
                    current = obj?.get(p.name) ?: return null
                    p = p.tail
                }
                is UiPath.Leaf -> return current
            }
        }
    }

    private fun setNode(doc: JsonObject, path: UiPath, value: JsonElement): JsonObject {
        val segments = path.toSegmentList()
        return setSegments(doc, segments, value)
    }

    private fun setSegments(doc: JsonObject, segments: List<String>, value: JsonElement): JsonObject {
        if (segments.isEmpty()) {
            return value as? JsonObject ?: JsonObject(emptyMap())
        }
        val key = segments[0]
        val rest = segments.drop(1)

        return if (rest.isEmpty()) {
            // Terminal segment — set the value directly
            JsonObject(doc.toMutableMap().apply { this[key] = value })
        } else {
            // Non-terminal — recursively build nested structure
            val child: JsonObject = doc[key] as? JsonObject ?: JsonObject(emptyMap())
            val updatedChild = setSegments(child, rest, value)
            JsonObject(doc.toMutableMap().apply { this[key] = updatedChild })
        }
    }
}

/** Returns a [UiPath.Segment] for the given string, inferring Int vs String. */
fun String.toSegment(): UiPath.Segment = when {
    this.all { it.isDigit() } -> UiPath.Segment.Index(this.toInt())
    else -> UiPath.Segment.Key(this)
}
