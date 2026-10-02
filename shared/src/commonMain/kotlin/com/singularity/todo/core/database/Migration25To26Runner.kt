package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Runner for migration from v25 to v26 — adds a real FOREIGN KEY from `notes.task_id`
 * to `tasks.id`.
 *
 * Before this migration, `task_id` was a nullable indexed column but had no FK constraint.
 * The `outgoing_links` JSON column carried `task://` URLs as a secondary encoding of the
 * same relationship.
 *
 * ## What this migration does
 *
 * 1. **Recreate `notes` with FK** — SQLite does not support `ALTER TABLE ADD CONSTRAINT`,
 *    so the table must be recreated with `FOREIGN KEY(task_id) REFERENCES tasks(id)
 *    ON DELETE SET NULL`.
 *
 * 2. **Backfill `task_id` from `outgoing_links`** — For every note where `task_id IS NULL`
 *    but `outgoing_links` contains a `task://<id>` entry, extract the first such ID and
 *    set it as `task_id`. The `ON DELETE SET NULL` policy means if the referenced task
 *    is later deleted, the note's `task_id` becomes NULL automatically.
 *
 * ## Why manual (not AutoMigrationSpec)
 *
 * AutoMigration cannot express "recreate table with FK constraint". Room would try to infer
 * a migration path and fail. This is the first hand-written migration in the project.
 *
 * ## Schema diff
 *
 * `notes` table gains:
 * - `FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE SET NULL`
 *
 * No new columns. No new indices. `outgoing_links` keeps its `task://` entries for
 * backward compatibility with existing backlink readers.
 *
 * @see com.singularity.todo.feature.notes.LinkSchemes
 */
@Suppress("ClassName")
object Migration25To26Runner {
    suspend operator fun invoke(connection: SQLiteConnection) {
        // 1. Create new notes table with FK constraint
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notes_new` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `title` TEXT NOT NULL DEFAULT '',
                `body_markdown` TEXT,
                `body_html` TEXT,
                `is_folder` INTEGER NOT NULL DEFAULT 0,
                `kind` TEXT NOT NULL DEFAULT 'Plain',
                `parent_note_id` TEXT,
                `is_pinned` INTEGER NOT NULL DEFAULT 0,
                `pinned_at` INTEGER,
                `color` INTEGER,
                `sort_order` INTEGER NOT NULL DEFAULT 0,
                `word_count` INTEGER NOT NULL DEFAULT 0,
                `char_count` INTEGER NOT NULL DEFAULT 0,
                `outgoing_links` TEXT NOT NULL DEFAULT '[]',
                `task_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `deleted_at` INTEGER,
                `archived_at` INTEGER,
                `server_version` INTEGER NOT NULL DEFAULT 0,
                `sync_status` TEXT NOT NULL DEFAULT '',
                `sync_error` TEXT,
                `last_synced_at` INTEGER,
                `device_id` TEXT,
                `hlc` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON DELETE SET NULL
            )
            """,
        )

        // 2. Copy all rows, backfilling task_id from outgoing_links where needed.
        // json_each() iterates JSON array elements; SUBSTR(value, 9) strips "task://".
        // COALESCE preserves any existing non-NULL task_id.
        connection.execSQL(
            """
            INSERT INTO `notes_new` (
                `id`, `user_id`, `title`, `body_markdown`, `body_html`,
                `is_folder`, `kind`, `parent_note_id`, `is_pinned`, `pinned_at`,
                `color`, `sort_order`, `word_count`, `char_count`,
                `outgoing_links`, `task_id`, `created_at`, `updated_at`,
                `deleted_at`, `archived_at`,
                `server_version`, `sync_status`, `sync_error`,
                `last_synced_at`, `device_id`, `hlc`
            )
            SELECT
                n.`id`, n.`user_id`, n.`title`, n.`body_markdown`, n.`body_html`,
                n.`is_folder`, n.`kind`, n.`parent_note_id`, n.`is_pinned`, n.`pinned_at`,
                n.`color`, n.`sort_order`, n.`word_count`, n.`char_count`,
                n.`outgoing_links`,
                COALESCE(
                    n.`task_id`,
                    (SELECT SUBSTR(je.value, 9)
                     FROM json_each(n.`outgoing_links`) AS je
                     WHERE je.value LIKE 'task://%'
                     LIMIT 1)
                ) AS `task_id`,
                n.`created_at`, n.`updated_at`,
                n.`deleted_at`, n.`archived_at`,
                n.`server_version`, n.`sync_status`, n.`sync_error`,
                n.`last_synced_at`, n.`device_id`, n.`hlc`
            FROM `notes` AS n
            """,
        )

        // 3. Drop old table and rename new one
        connection.execSQL("DROP TABLE `notes`")
        connection.execSQL("ALTER TABLE `notes_new` RENAME TO `notes`")

        // 4. Recreate indices (SQLite does not carry them across RENAME TABLE)
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_user_id` ON `notes` (`user_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_deleted_at` ON `notes` (`deleted_at`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_parent_note_id` ON `notes` (`parent_note_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_is_pinned` ON `notes` (`is_pinned`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_archived_at` ON `notes` (`archived_at`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_sort_order` ON `notes` (`sort_order`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_task_id` ON `notes` (`task_id`)")
    }
}
