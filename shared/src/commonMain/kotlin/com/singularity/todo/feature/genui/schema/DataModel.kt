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

    /**
     * Writing is [JsonPathWriter]'s job.
     *
     * It has to choose the *kind* of every node it creates along the path — array for an index,
     * object for a name — and this class is about storing and observing the result of that choice,
     * not about making it.
     */
    private fun setNode(doc: JsonObject, path: UiPath, value: JsonElement): JsonObject =
        JsonPathWriter.write(doc, path.toSegmentList(), value)
}

/** Returns a [UiPath.Segment] for the given string, inferring Int vs String. */
fun String.toSegment(): UiPath.Segment = when {
    this.all { it.isDigit() } -> UiPath.Segment.Index(this.toInt())
    else -> UiPath.Segment.Key(this)
}
