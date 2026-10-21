package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v42 to v43 — the full-text indexes.
 *
 * Creates the four FTS4 indexes of the searchable kinds — tasks, notes,
 * projects and tags — and fills them with the rows that already exist.
 *
 * ## Why a manual migration and not an auto-migration
 *
 * An index is not a table. `CREATE VIRTUAL TABLE … USING FTS4` is a statement
 * Room's schema differ cannot describe, and the rows that are already on disk
 * have to be put into the index — a `rebuild`, which is data movement, and
 * data movement is not something an `AutoMigrationSpec` can express.
 *
 * The migration is registered by hand in [AppDatabaseFactory] for the same
 * reason [Migration31To32] is.
 *
 * ## The tables are *external content*
 *
 * Each index names the table it belongs to (`content=\`tasks\``), so the text
 * is not copied: the index holds the token postings and the row is read from
 * the content table when a query returns it. A database of ten thousand tasks
 * grows by an index, not by a second copy of every title and description.
 *
 * ## The triggers are Room's, not ours
 *
 * Keeping the index in step with the content table needs triggers on
 * `tasks`, `notes`, `projects` and `tags`. Room installs them itself, right
 * after the migration, under the names `room_fts_content_sync_*`. They are
 * deliberately not created here: a second copy of a trigger Room also creates
 * is a second thing to keep in step, and `IF NOT EXISTS` would only hide the
 * disagreement.
 *
 * ## Why the index is filled with `rebuild`
 *
 * The rows that were written before this migration are not in the index, and a
 * search would not find them — the search would silently return fewer results
 * than the database holds, which is the kind of failure nobody reports. The
 * `rebuild` command re-reads the content table and writes the postings for
 * every row, so the index is complete the moment the migration ends.
 *
 * The order matters: the table is created first, because `rebuild` writes into
 * it, and the triggers come after, because a trigger firing during the
 * rebuild would write every row twice.
 */
class Migration42To43 : Migration(42, 43) {
    override suspend fun migrate(connection: SQLiteConnection) {
        createIndex(connection, "tasks_fts", "tasks", listOf("`title` TEXT NOT NULL", "`description` TEXT"))
        createIndex(connection, "notes_fts", "notes", listOf("`title` TEXT NOT NULL"))
        createIndex(connection, "projects_fts", "projects", listOf("`name` TEXT NOT NULL"))
        createIndex(connection, "tags_fts", "tags", listOf("`name` TEXT NOT NULL"))
    }

    /**
     * Creates one external-content FTS4 index and fills it from its content
     * table.
     *
     * The statement has to match the one Room generates for the corresponding
     * entity: Room validates the schema of an FTS table against the statement
     * it would have created itself, and a mismatch there is a crash on the
     * next open rather than a warning.
     */
    private suspend fun createIndex(
        connection: SQLiteConnection,
        indexTable: String,
        contentTable: String,
        columns: List<String>,
    ) {
        val columnList = columns.joinToString(separator = ", ")
        connection.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `$indexTable` " +
                "USING FTS4($columnList, tokenize=unicode61, content=`$contentTable`)",
        )
        connection.execSQL("INSERT INTO `$indexTable`(`$indexTable`) VALUES('rebuild')")
    }
}
