package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v54 to v55 — adds `sort_order` to tasks.
 *
 * Subtasks are tasks with a [TaskEntity.parentTaskId]; their order is the
 * order the Subtasks section restores on reopen. `0` is the right default for
 * rows that predate the column: they keep their previous relative position
 * under the existing reads until the user reorders them.
 *
 * The column lives on the synced task document, so the wire allow-list and the
 * server allow-list gain `task.sortOrder` in the same change.
 *
 * Written by hand for the same reason as [Migration45To46] and
 * [Migration46To47]: the derived auto-migration rewrites the whole `tasks`
 * table, and dropping it fires `notes.task_id ON DELETE SET NULL`.
 */
class Migration54To55 : Migration(54, 55) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tasks` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
