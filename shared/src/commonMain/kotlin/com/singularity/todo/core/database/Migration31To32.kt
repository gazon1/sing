package com.singularity.todo.core.database

import androidx.room3.DeleteColumn
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection

/**
 * Migration from v31 to v32 — drops the legacy `task_id` column.
 *
 * After the schema migration, [onPostMigrate] backfills `target_id` from `task_id`
 * for any rows written by builds that populated `task_id` but not `target_id`.
 *
 * This is safe to run multiple times: rows where `target_id` is already populated
 * are updated with the same value (no-op), and rows where both are NULL are skipped.
 */
@DeleteColumn(tableName = "ai_proposal", columnName = "task_id")
class Migration31To32 : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        val stmt = connection.prepare(
            """
            UPDATE ai_proposal
            SET target_id = task_id
            WHERE target_id IS NULL AND task_id IS NOT NULL
            """.trimIndent(),
        )
        stmt.use { it.step() }
    }
}
