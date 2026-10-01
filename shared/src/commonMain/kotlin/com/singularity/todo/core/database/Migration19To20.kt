package com.singularity.todo.core.database

import androidx.room3.RenameColumn
import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v19 to v20 — adds `outgoing_links` column to tasks, renames
 * `parent_id` to `group_id` in tags (tag group hierarchy), and adds `tag_groups`
 * table for tag group hierarchy support.
 *
 * 1. `tasks.outgoing_links TEXT NOT NULL DEFAULT '[]'` — stores wikilink tokens
 *    pointing from this task to other tasks (for future backlinks UI).
 * 2. `tags.parent_id` → `tags.group_id` — renamed to avoid confusion with
 *    the parent-tag hierarchy (tags can now belong to a tag group, not a parent tag).
 * 3. `tag_groups` table — stores tag group entities with (id, user_id) composite PK
 *    and `name`, `sort_order` columns. Tags reference their group via `group_id`.
 *
 * No destructive changes: all existing rows continue to work.
 */
@RenameColumn(
    tableName = "tags",
    fromColumnName = "parent_id",
    toColumnName = "group_id",
)
class Migration19To20 : AutoMigrationSpec
