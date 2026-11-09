package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v37 to v38 — the outbox learns whose work it is holding.
 *
 * Adds `owner_id` to `sync_outbox` and `sync_dead_letter`, and **clears both tables**.
 *
 * ## Why these tables had no owner
 *
 * `sync_shadow` and `sync_state` have carried `owner_id` since the beginning, so the
 * device knew which account a *row* belonged to. The queue carrying the changes to
 * that row did not, and so was a bag of patches rather than per-account work. Two
 * consequences, both live rather than hypothetical:
 *
 * - `getPending` returned every row regardless of who wrote it, and `planPush` built
 *   one request from all of them under the active scope. Work queued by one account
 *   therefore left the device inside another account's authenticated request. #209.
 * - REQ-UA-017 could not be written. The decided switch — deliver the departing
 *   account's queued work, then erase that account's local data — has no selection and
 *   no scope against an unattributable queue, and an owner-scoped delete of the outbox
 *   would have taken the *incoming* account's queued work along with it.
 *
 * ## Why the tables are cleared rather than backfilled
 *
 * A pre-existing row cannot be attributed. Guessing would file one account's unsent
 * work under another, which is the exact failure this column removes — and it would do
 * so silently, to work the user cannot see. Clearing states plainly that nothing here
 * is known to be anybody's.
 *
 * The cost is real and is the reason this is a decision rather than a default: queued
 * work that had not yet been delivered is lost by the upgrade. That work is, by
 * definition, work the server never saw, so what is destroyed is a local copy of
 * edits the user made. It is accepted because the alternative is not a smaller loss —
 * it is a silent misattribution, which is the worse failure and the one that would
 * keep happening after every subsequent upgrade.
 *
 * A migration that could ask would not be worth having: it runs inside the user's
 * process, unattended, where an interactive prompt is not available and a background
 * worker cannot answer one.
 *
 * ## Why the column carries a SQL default but the constructor does not
 *
 * SQLite will not add a `NOT NULL` column without one, so the migration supplies
 * `DEFAULT ''` and the entity declares the same value — Room validates the migrated
 * schema against the entity, and a mismatch here is a crash on upgrade rather than a
 * warning.
 *
 * The guarantee does not live there, though. It lives in the Kotlin constructor, which
 * has **no** default: every construction site states its owner or fails to compile. The
 * SQL default is only what makes the `ALTER` legal, and the `DELETE` below it runs
 * immediately after, so no live row is ever left with the empty string standing in for
 * an owner it does not have.
 */
class Migration37To38 : Migration(37, 38) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf("sync_outbox", "sync_dead_letter").forEach { table ->
            connection.execSQL("ALTER TABLE $table ADD COLUMN owner_id TEXT NOT NULL DEFAULT ''")
            // After the rows are gone, so the default is unreachable by any live row:
            // it exists only so the ALTER above can add a NOT NULL column to a table
            // that still has rows, and the delete immediately follows.
            connection.execSQL("DELETE FROM $table")
        }
    }
}
