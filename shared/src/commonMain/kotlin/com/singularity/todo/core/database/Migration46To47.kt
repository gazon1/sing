package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v46 to v47 — adds the `workflow_state` column to tasks.
 *
 * A task now carries the state the user has claimed for its work: `Open`,
 * `NextUp` or `InProgress`. The state is stored by name, and the column has a
 * constant default, so every task that existed before the upgrade reads back as
 * `Open` — which is what "nothing claimed" means. Nothing is backfilled and
 * nothing can be lost.
 *
 * ## Why this migration is written by hand
 *
 * Room's auto-migration would derive this change from the entity, and the
 * derived migration **rewrites the whole `tasks` table**: it creates
 * `_new_tasks`, copies every row, drops `tasks` and renames the copy back. That
 * rewrite is not free of side effects, and one of them destroys data the app
 * never asked to lose:
 *
 * - `notes.task_id` is a foreign key onto `tasks(id)` with `ON DELETE SET NULL`.
 *   SQLite runs the foreign-key actions of a dropped table as an implicit
 *   `DELETE FROM tasks`, so every note that pointed at a task sees its
 *   `task_id` become `NULL`. The note survives; the relation does not.
 * - The rewrite copies the rows through a second table, so every row is written
 *   twice for a change that only needs a column added.
 *
 * `ALTER TABLE ... ADD COLUMN` writes neither the rows nor the relations.
 * `Migration45To46` is the same shape of change and is manual for the same
 * reason.
 *
 * ## Why there is no index
 *
 * The agenda matches the workflow state in memory, over the task list it has
 * already loaded — the same way the blocked selector matches against the set of
 * blocked task ids. No query filters on this column, so an index on it would be
 * weight without a query.
 *
 * @see com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
 * @see Migration45To46
 */
class Migration46To47 : Migration(46, 47) {

    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tasks` ADD COLUMN `workflow_state` TEXT NOT NULL DEFAULT 'Open'",
        )
    }
}
