package com.singularity.todo.core.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object BackupMigrations {
    const val CURRENT = BackupFormat.SCHEMA_VERSION

    /**
     * v1 → v2: Add fields that were missing from TaskDto and NoteDto in v1.
     *
     * TaskDto gained: parentTaskId, estimateMinutes, recurrenceRule, outgoingLinks,
     *                  aiSuppressedTagIds
     * NoteDto gained:  isPinned, pinnedAt, color, sortOrder, wordCount, charCount,
     *                  outgoingLinks, taskId
     *
     * All new fields default to null/empty-string/0 so that a v1 backup is fully
     * restorable on a v2 client.
     */
    private val v1ToV2: (JsonObject) -> JsonObject = { payload ->
        val tasks = payload["tasks"]?.jsonArray?.map { task ->
            val obj = task.jsonObject
            JsonObject(
                obj + mapOf(
                    "parentTaskId" to JsonPrimitive(null),
                    "estimateMinutes" to JsonPrimitive(null),
                    "recurrenceRule" to JsonPrimitive(null),
                    "outgoingLinks" to JsonArray(listOf()),
                    "aiSuppressedTagIds" to JsonArray(listOf()),
                ),
            )
        } ?: emptyList()
        val notes = payload["notes"]?.jsonArray?.map { note ->
            val obj = note.jsonObject
            JsonObject(
                obj + mapOf(
                    "isPinned" to JsonPrimitive(false),
                    "pinnedAt" to JsonPrimitive(null),
                    "color" to JsonPrimitive(null),
                    "sortOrder" to JsonPrimitive(0),
                    "wordCount" to JsonPrimitive(0),
                    "charCount" to JsonPrimitive(0),
                    "outgoingLinks" to JsonArray(listOf()),
                    "taskId" to JsonPrimitive(null),
                ),
            )
        } ?: emptyList()
        JsonObject(
            payload + mapOf(
                "tasks" to JsonArray(tasks),
                "notes" to JsonArray(notes),
                "schemaVersion" to JsonPrimitive(2),
            ),
        )
    }

    /**
     * v3 → v4: Add 8 new entity types.
     *
     * All new list fields default to empty in the serializable class, so a v3 payload
     * is fully restorable on a v4 client without any field-level transformation.
     * This migration only bumps the schema version for correctness.
     */
    private val v3ToV4: (JsonObject) -> JsonObject = { payload ->
        JsonObject(payload + mapOf("schemaVersion" to JsonPrimitive(4)))
    }

    // Map<fromVersion, transform>
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        1 to v1ToV2,
        3 to v3ToV4,
    )

    /**
     * Migrate a payload from `from` to `to` schema version.
     * Applies migrations sequentially: v1→v2→v3→...→to
     */
    fun migrate(payload: JsonObject, from: Int, to: Int = CURRENT): JsonObject {
        require(from <= to) { "from ($from) > to ($to)" }
        return (from until to).fold(payload) { acc, v ->
            migrations[v]?.invoke(acc) ?: acc
        }
    }
}
