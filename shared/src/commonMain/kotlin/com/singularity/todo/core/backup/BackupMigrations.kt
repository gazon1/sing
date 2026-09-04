package com.singularity.todo.core.backup

import kotlinx.serialization.json.JsonObject

object BackupMigrations {
    const val CURRENT = BackupFormat.SCHEMA_VERSION

    // Map<fromVersion, transform>
    // Add as: migrations[1] = { obj -> migrateV1ToV2(obj) }
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

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
