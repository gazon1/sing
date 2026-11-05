package com.singularity.todo.core.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object BackupMigrations {
    const val CURRENT = BackupFormat.SCHEMA_VERSION

    /**
     * v1 → v2: the fields the first version did not write.
     *
     * TaskDto gained: parentTaskId, estimateMinutes, recurrenceRule, outgoingLinks,
     *                  aiSuppressedTagIds
     * NoteDto gained:  isPinned, pinnedAt, color, sortOrder, wordCount, charCount,
     *                  outgoingLinks, taskId
     *
     * Every field written here is one the payload's DTOs read. A key no DTO
     * carries is dropped on decode (`ignoreUnknownKeys`), so the payload would
     * promise a value its reader never gets — the reason this step and
     * [TaskDto] are kept in step.
     *
     * `parentTaskId` is written as `null` on every task, and that is a statement
     * about the archive rather than about the account: a v1 payload never
     * carried the parent link, so the rows of a migrated v1 backup come back as
     * top-level tasks. The step says so on every task instead of leaving the
     * reader to the DTO's default, the same way the later steps do.
     */
    private val v1ToV2: (JsonObject) -> JsonObject = { payload ->
        val tasks =
            payload["tasks"]?.jsonArray?.map { task ->
                val obj = task.jsonObject
                JsonObject(
                    obj +
                        mapOf(
                            "parentTaskId" to JsonPrimitive(null),
                            "estimateMinutes" to JsonPrimitive(null),
                            "recurrenceRule" to JsonPrimitive(null),
                            "outgoingLinks" to JsonArray(listOf()),
                            "aiSuppressedTagIds" to JsonArray(listOf()),
                        ),
                )
            } ?: emptyList()
        val notes =
            payload["notes"]?.jsonArray?.map { note ->
                val obj = note.jsonObject
                JsonObject(
                    obj +
                        mapOf(
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
            payload +
                mapOf(
                    "tasks" to JsonArray(tasks),
                    "notes" to JsonArray(notes),
                    "schemaVersion" to JsonPrimitive(2),
                ),
        )
    }

    /**
     * v2 → v3: a payload can carry the saved agenda views.
     *
     * A v2 payload knows about the dependency edges and nothing else. The new
     * list defaults to empty in the serializable class, so a v2 payload is
     * restorable on a v3 client without any field-level transformation — but the
     * payload that comes out of this migration says so, rather than leaving the
     * reader to know the default to read it.
     */
    private val v2ToV3: (JsonObject) -> JsonObject = { payload ->
        JsonObject(
            payload +
                mapOf(
                    "agendaViews" to JsonArray(payload["agendaViews"]?.jsonArray ?: emptyList()),
                    "schemaVersion" to JsonPrimitive(3),
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

    /**
     * v4 → v5: a dependency edge carries its verb.
     *
     * A v4 payload has only the two ends of each edge, so every relation in it
     * is a block. The transform says so on every edge rather than leaving it to
     * the default in the DTO: the payload that is written after the migration
     * is the payload that says what its edges mean, and a reader that has to
     * know the default to read it is one version away from a wrong answer.
     */
    private val v4ToV5: (JsonObject) -> JsonObject = { payload ->
        val dependencies =
            payload["taskDependencies"]?.jsonArray?.map { dependency ->
                JsonObject(dependency.jsonObject + mapOf("verb" to JsonPrimitive("BLOCKS")))
            } ?: emptyList()
        JsonObject(
            payload +
                mapOf(
                    "taskDependencies" to JsonArray(dependencies),
                    "schemaVersion" to JsonPrimitive(5),
                ),
        )
    }

    /**
     * v5 → v6: a task record carries the state the user claimed for its work.
     *
     * A v5 payload has no such field, so every task comes back as `Open` — which
     * is what "nothing claimed" means. The transform says so on every task
     * rather than leaving it to the default in the DTO: the payload that is
     * written after the migration is the payload that says what its tasks mean,
     * and a reader that has to know the default to read it is one version away
     * from a wrong answer.
     */
    private val v5ToV6: (JsonObject) -> JsonObject = { payload ->
        val tasks =
            payload["tasks"]?.jsonArray?.map { task ->
                JsonObject(task.jsonObject + mapOf("workflowState" to JsonPrimitive("Open")))
            } ?: emptyList()
        JsonObject(
            payload +
                mapOf(
                    "tasks" to JsonArray(tasks),
                    "schemaVersion" to JsonPrimitive(6),
                ),
        )
    }

    /**
     * v6 → v7: a payload can carry the rows that were removed.
     *
     * The new field is optional and defaults to empty, so a v6 payload says
     * everything it always said: the rows it holds are the whole account, and
     * nothing was removed. All this migration does is bump the version, so that
     * the payload that comes out of it is the payload this build writes.
     */
    private val v6ToV7: (JsonObject) -> JsonObject = { payload ->
        JsonObject(payload + mapOf("schemaVersion" to JsonPrimitive(7)))
    }

    // Map<fromVersion, transform>
    private val migrations: Map<Int, (JsonObject) -> JsonObject> =
        mapOf(
            1 to v1ToV2,
            2 to v2ToV3,
            3 to v3ToV4,
            4 to v4ToV5,
            5 to v5ToV6,
            6 to v6ToV7,
        )

    /**
     * Migrate a payload from `from` to `to` schema version.
     * Applies migrations sequentially: v1→v2→v3→...→to
     *
     * A step that is not registered is an **error**, not a no-op. The fold used
     * to skip it, so a payload written at a version the chain does not cover
     * walked over the gap and came out as the payload it went in as — with
     * whatever the DTO defaults happened to be. The symptom was "my data is
     * wrong", not "this backup is too old".
     */
    fun migrate(
        payload: JsonObject,
        from: Int,
        to: Int = CURRENT,
    ): JsonObject {
        require(from <= to) { "from ($from) > to ($to)" }
        return (from until to).fold(payload) { acc, v ->
            val step = migrations[v] ?: error("no migration from schema v$v")
            step(acc)
        }
    }
}
