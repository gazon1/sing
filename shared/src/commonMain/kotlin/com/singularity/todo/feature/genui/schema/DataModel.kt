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
 * ## The count of the server's rewrites lives here, with the value
 *
 * A field bound to a path needs two facts about that path: the value, and the count of
 * the times the server replaced it. The count is what tells a deliberate rewrite apart
 * from a lagging model, and it belongs to the **path it rewrote** — a write to one path
 * says nothing about another.
 *
 * So the count is stored here, next to the value, and the two are read together:
 * [valueAt] returns both in one read, [flowAt] emits both in one emission. Two stores —
 * the document here and a counter beside it — would be two facts that can disagree, and
 * a caller that read them separately could pair a value with a count that does not go
 * with it.
 *
 * Thread-safe via [MutableStateFlow] (which is conflated — last-write-wins).
 */
class DataModel(initial: JsonObject = JsonObject(emptyMap())) {

    /**
     * The document and the count of the server's rewrites, per path, in one state.
     *
     * One state, not two: a reader that takes the document and the revisions from two
     * places can read them between two writes and pair a value with a count that does
     * not belong to it.
     */
    private val state = MutableStateFlow(Document(initial, emptyMap()))

    /** Returns the value at [path], or null if not found. */
    fun get(path: UiPath): JsonElement? {
        val doc = state.value.json
        if (path is UiPath.Root) return doc
        return resolve(path, doc)
    }

    /**
     * The count of the times the server replaced the value at [path].
     *
     * Zero until the server writes to that path for the first time. It is a *token*, not
     * a value: any change to it means "the value that comes with it was rewritten from
     * outside".
     */
    fun revision(path: UiPath): Long = state.value.revisions[path.toPointer()] ?: 0L

    /**
     * The value at [path] and the count of the server's rewrites of it — read together.
     *
     * One read, so the pair cannot be torn.
     */
    fun valueAt(path: UiPath): PathValue = with(state.value) {
        PathValue(
            value = if (path is UiPath.Root) json else resolve(path, json),
            revision = revisions[path.toPointer()] ?: 0L,
        )
    }

    /**
     * A [Flow] of the value at [path] and the count of the server's rewrites of it.
     *
     * One emission carries both, so a collector never sees a value with a count that
     * belongs to another write.
     */
    fun flowAt(path: UiPath): Flow<PathValue> = state.map { doc ->
        PathValue(
            value = if (path is UiPath.Root) doc.json else resolve(path, doc.json),
            revision = doc.revisions[path.toPointer()] ?: 0L,
        )
    }

    /**
     * Sets the value at [path], creating intermediate objects as needed.
     *
     * The count does not move: this is the field's own write, the model talking to
     * itself. A write from outside is [setFromServer].
     */
    fun set(path: UiPath, value: JsonElement) {
        val current = state.value
        state.value = current.copy(json = writeAt(current.json, path, value))
    }

    /**
     * Sets the value at [path] as a rewrite from outside, and moves that path's count.
     *
     * The count is the *only* way to say "this value was replaced from outside", and it
     * moves for this path alone: a rewrite of one path says nothing about another.
     */
    fun setFromServer(path: UiPath, value: JsonElement) {
        val current = state.value
        val key = path.toPointer()
        val revision = (current.revisions[key] ?: 0L) + 1L
        state.value = Document(
            json = writeAt(current.json, path, value),
            revisions = current.revisions + (key to revision),
        )
    }

    /** A [Flow] of the value at [path], emitting on every change. */
    fun flow(path: UiPath): Flow<JsonElement?> = state.map { doc ->
        if (path is UiPath.Root) doc.json else resolve(path, doc.json)
    }

    /** Returns a snapshot of the entire document as [JsonObject]. */
    fun snapshot(): JsonObject = state.value.json

    override fun toString(): String = "DataModel(${state.value.json})"

    // ─── Pure helpers ─────────────────────────────────────────────────────

    /**
     * The document and the revisions — the two things that are always read together.
     */
    private data class Document(val json: JsonObject, val revisions: Map<String, Long>)

    /** The document with [value] written at [path]. */
    private fun writeAt(doc: JsonObject, path: UiPath, value: JsonElement): JsonObject =
        if (path is UiPath.Root) {
            value as? JsonObject ?: JsonObject(emptyMap())
        } else {
            setNode(doc, path, value)
        }

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
