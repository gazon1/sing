package com.singularity.todo.feature.genui.schema

import com.singularity.todo.core.text.Revisioned
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
 * ## The pair is the same pair the fields use
 *
 * The value and the count travel in a [Revisioned] — the type the text fields carry
 * their own count in. It used to be a separate `PathValue` of this package, declared
 * because "the schema layer must not depend on the UI layer's types": the GenUI's data
 * model knows nothing about text fields. That reason is gone — the contract types are
 * neutral now, they live in `com.singularity.todo.core.text` and say nothing about a
 * field — so there is one pair of "a value and the count of its replacements" in the
 * app, and the schema layer uses it.
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
    fun revision(path: UiPath): Long = state.value.revisions[path.toPointer()]?.revision ?: 0L

    /**
     * The value at [path] and the count of the server's rewrites of it — read together.
     *
     * One read, so the pair cannot be torn.
     */
    fun valueAt(path: UiPath): Revisioned<JsonElement?> = state.value.revisionedAt(path)

    /**
     * A [Flow] of the value at [path] and the count of the server's rewrites of it.
     *
     * One emission carries both, so a collector never sees a value with a count that
     * belongs to another write.
     */
    fun flowAt(path: UiPath): Flow<Revisioned<JsonElement?>> = state.map { doc ->
        doc.revisionedAt(path)
    }

    /**
     * Sets the value at [path], creating intermediate objects as needed.
     *
     * The count does not move: this is the field's own write, the model talking to
     * itself. A write from outside is [setFromServer].
     */
    fun set(path: UiPath, value: JsonElement) {
        val current = state.value
        val key = path.toPointer()
        // The write is the field's own, so the count stays where it is — `typed` is the
        // move that changes a value without announcing a replacement.
        val revisioned: Revisioned<JsonElement?> =
            current.revisions[key]?.typed(value) ?: Revisioned.initial(value)
        state.value = current.copy(
            json = writeAt(current.json, path, value),
            revisions = current.revisions + (key to revisioned),
        )
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
        // A write from outside is a replacement, so the count moves — `replacedBy` is the
        // only move that moves it.
        val previous: Revisioned<JsonElement?> =
            current.revisions[key] ?: Revisioned.initial(value)
        state.value = Document(
            json = writeAt(current.json, path, value),
            revisions = current.revisions + (key to previous.replacedBy(value)),
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
     *
     * The revision of a path is kept as a [Revisioned] rather than as a bare count: the
     * count and the value it counts are two facts about the same thing, and the type
     * that carries both is the one the fields carry their own count in.
     */
    private data class Document(
        val json: JsonObject,
        val revisions: Map<String, Revisioned<JsonElement?>>,
    )

    /**
     * The value at [path] and the count of the server's rewrites of it, as one pair.
     *
     * The value is read from the document, because the document is where the value
     * lives; the count is read from the path's entry, because that is where the count
     * lives. The two are put back together here, and they cannot be torn: the document
     * and the entries are one state.
     *
     * The stored value of an entry can be older than the document's — a write to the
     * root of the document replaces everything under it, and the entries of the paths
     * below keep their count, because that count is the count of *that path's* rewrites.
     * So the value is refreshed with [Revisioned.typed], which changes the value and
     * leaves the count where it is: the count does not move for a value that was never
     * replaced.
     */
    private fun Document.revisionedAt(path: UiPath): Revisioned<JsonElement?> {
        val resolved = if (path is UiPath.Root) json else resolve(path, json)
        return revisions[path.toPointer()]?.typed(resolved) ?: Revisioned.initial(resolved)
    }

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
