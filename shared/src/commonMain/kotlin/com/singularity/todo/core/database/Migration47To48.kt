package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v47 to v48 — tag name uniqueness.
 *
 * ## What it does
 *
 * 1. Adds the `name_normalized` column, holding `lower(trim(name))`. This is the
 *    key the uniqueness rule compares on: "Invoice" and "invoice " collapse to
 *    the same value, so two tags that a person cannot tell apart become one row.
 * 2. Merges the live duplicates: for each `(user_id, name_normalized)` group
 *    with more than one live row, the earliest row is kept, the `task_tags`
 *    rows that pointed at the duplicates are re-pointed at the survivor, and
 *    the duplicate tag rows are deleted.
 * 3. Creates a partial unique index so the database itself refuses a second
 *    live tag with the same normalized name. Soft-deleted rows are excluded,
 *    because a trashed tag must not block recreating the label.
 *
 * ## Why the column rather than an expression index
 *
 * SQLite can express the rule as `CREATE UNIQUE INDEX ... ON tags(user_id, lower(trim(name))) WHERE deleted_at IS NULL`,
 * but Room's `@Index` cannot describe a partial or expression index, so the
 * schema the entity declares would not match the database Room opens. A real
 * column keeps the entity and the database in agreement, and the unique index
 * is still created by hand here because Room cannot express the `WHERE` clause.
 *
 * @see com.singularity.todo.feature.tags.domain.TagDomain
 */
class Migration47To48 : Migration(47, 48) {

    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tags` ADD COLUMN `name_normalized` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL(
            "UPDATE `tags` SET `name_normalized` = lower(trim(`name`))",
        )

        // Re-point task_tags rows that reference a duplicate live tag at the
        // survivor of its (user_id, name_normalized) group. The survivor is the
        // row with the smallest id in the group.
        connection.execSQL(
            """
            UPDATE `task_tags`
            SET `tag_id` = (
                SELECT MIN(t2.id)
                FROM `tags` t2
                WHERE t2.user_id = (SELECT t.user_id FROM `tags` t WHERE t.id = `task_tags`.`tag_id`)
                  AND t2.name_normalized = (SELECT t.name_normalized FROM `tags` t WHERE t.id = `task_tags`.`tag_id`)
                  AND t2.deleted_at IS NULL
            )
            WHERE EXISTS (
                SELECT 1 FROM `tags` t
                WHERE t.id = `task_tags`.`tag_id`
                  AND t.deleted_at IS NULL
                  AND t.id <> (
                      SELECT MIN(t2.id)
                      FROM `tags` t2
                      WHERE t2.user_id = t.user_id
                        AND t2.name_normalized = t.name_normalized
                        AND t2.deleted_at IS NULL
                  )
            )
            """,
        )

        // Drop the duplicate live tag rows, keeping the smallest id per group.
        connection.execSQL(
            """
            DELETE FROM `tags`
            WHERE deleted_at IS NULL
              AND id NOT IN (
                  SELECT MIN(id) FROM `tags` WHERE deleted_at IS NULL GROUP BY user_id, name_normalized
              )
            """,
        )

        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_user_id_name_normalized` " +
                "ON `tags`(`user_id`, `name_normalized`) WHERE `deleted_at` IS NULL",
        )
    }
}
