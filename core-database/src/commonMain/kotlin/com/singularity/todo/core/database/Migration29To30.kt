package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v29 to v30 — adds sovereignty fields to checklist items
 * and tag suppression to tasks.
 *
 * - `checklist_items`: adds `checked_by TEXT?`, `checked_at INTEGER?`, `row_version INTEGER DEFAULT 1`
 * - `tasks`: adds `ai_suppressed_tag_ids TEXT DEFAULT '[]'`
 */
class Migration29To30 : AutoMigrationSpec
