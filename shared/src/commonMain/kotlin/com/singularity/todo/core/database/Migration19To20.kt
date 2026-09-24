package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v19 to v20:
 * - Adds `group_id TEXT` column to tags table (tag groups, replaces dead `parent_id`)
 * - Removes `parent_id` column from tags (dead schema replaced by tag_groups)
 *
 * Room KSP auto-infers both changes from schema diff (19.json → 20.json):
 * - @DeleteColumn removes parent_id from tags
 * - new group_id column is auto-detected as ADD COLUMN
 * No migrate() override needed.
 */
@androidx.room3.DeleteColumn(tableName = "tags", columnName = "parent_id")
class Migration19To20 : AutoMigrationSpec
