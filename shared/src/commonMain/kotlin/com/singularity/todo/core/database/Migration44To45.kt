package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.links.InternalLinkSchemes

/**
 * Migration from v44 to v45 — the stored links are read by the one grammar.
 *
 * ## What was wrong
 *
 * The column `outgoing_links` holds a JSON array of `scheme://id` URLs. The app had
 * one grammar for them (`core/links/InternalLinks.kt`) and several places that did not
 * use it:
 *
 * 1. **The note-to-task relation was never backfilled.** `notes.task_id` was added
 *    early and the foreign key came later, but no migration ever filled the column
 *    from the links a note already carried. `Migration25To26Runner` was written to do
 *    exactly that and was never wired into the chain — it was dead code that encoded
 *    the grammar a second time, with the prefix length written as the number 9.
 * 2. **Links the grammar rejects stayed in the column.** A link with an identifier
 *    outside `[A-Za-z0-9_-]` can never be opened by anything, so it is a row that
 *    claims a relation that does not exist.
 *
 * ## What this migration does
 *
 * 1. **Backfills `notes.task_id`** from the note's first `task://` link whose
 *    identifier the grammar accepts.
 * 2. **Drops the links the grammar rejects** from `tasks.outgoing_links` and
 *    `notes.outgoing_links`.
 *
 * ## Why the prefix is derived
 *
 * `SUBSTR` is one-based, and the old runner wrote `SUBSTR(je.value, 9)` — the length
 * of `"task://"` as a literal. A grammar that lives in one place and a migration that
 * spells it out are two grammars. The prefix, its length and the identifier's
 * character class all come from [InternalLinkSchemes] here, so a change to the
 * vocabulary moves this migration with it.
 *
 * ## What it does not do
 *
 * It does not change what a link looks like to the person, and it does not change the
 * column's format: the column still holds a JSON array of URL strings.
 */
class Migration44To45 : Migration(44, 45) {

    override suspend fun migrate(connection: SQLiteConnection) {
        backfillNoteTaskIds(connection)
        dropUnreadableLinks(connection, "notes")
        dropUnreadableLinks(connection, "tasks")
    }

    /**
     * The identifier's character class, in the form SQLite's `GLOB` wants it.
     *
     * The grammar states the rule as a regular expression; `GLOB` wants the same class
     * without the quantifier. Deriving it keeps the two from drifting apart — a new
     * character allowed in an identifier is allowed here too.
     */
    private val idClass: String = InternalLinkSchemes.ID_PATTERN
        .removePrefix("[")
        .removeSuffix("]+")

    /**
     * A `GLOB` that matches a whole link of one scheme and nothing else.
     *
     * `GLOB`'s `*` matches anything, so the pattern alone would accept
     * `task://abc/def`. The second condition says the identifier — everything after
     * the prefix — contains no character outside the class, and the third says it is
     * not empty.
     */
    private fun linkGlob(scheme: String): String {
        val prefix = scheme + InternalLinkSchemes.SEPARATOR
        // SUBSTR is one-based: the first character of the identifier is at prefix.length + 1.
        val idStart = prefix.length + 1
        return "(je.value GLOB '$prefix*' " +
            "AND LENGTH(SUBSTR(je.value, $idStart)) > 0 " +
            "AND SUBSTR(je.value, $idStart) NOT GLOB '*[^$idClass]*')"
    }

    /**
     * Fills `notes.task_id` from the note's own links.
     *
     * Only for notes that have no task yet: a note that already points at one keeps
     * the task it points at, even if it also links to another.
     */
    private fun backfillNoteTaskIds(connection: SQLiteConnection) {
        val prefix = InternalLinkSchemes.TASK_PREFIX
        val idStart = prefix.length + 1
        connection.execSQL(
            """
            UPDATE notes
            SET task_id = (
                SELECT SUBSTR(je.value, $idStart)
                FROM json_each(notes.outgoing_links) AS je
                WHERE ${linkGlob(InternalLinkSchemes.TASK)}
                LIMIT 1
            )
            WHERE task_id IS NULL
              AND EXISTS (
                SELECT 1 FROM json_each(notes.outgoing_links) AS je
                WHERE ${linkGlob(InternalLinkSchemes.TASK)}
              )
            """,
        )
    }

    /**
     * Rewrites the column so that it holds only the links the grammar accepts.
     *
     * A link that cannot be opened is not a relation; keeping it in the column makes
     * the column claim something the app cannot deliver.
     */
    private fun dropUnreadableLinks(connection: SQLiteConnection, table: String) {
        val globs = InternalLinkSchemes.ALL
            .map { linkGlob(it) }
            .joinToString(" OR ")
        connection.execSQL(
            """
            UPDATE $table
            SET outgoing_links = (
                SELECT COALESCE(json_group_array(je.value), '[]')
                FROM json_each($table.outgoing_links) AS je
                WHERE $globs
            )
            """,
        )
    }
}
