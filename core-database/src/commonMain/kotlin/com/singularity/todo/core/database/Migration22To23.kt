package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v22 to v23 — adds `project_reminders` table.
 *
 * Stores project-level reminders (e.g., "remind me when this project
 * has no open tasks for 3 days"). Mirror of `task_reminders` but for
 * projects. Enables the project reminder feature without coupling to
 * task entities.
 *
 * Tables added:
 * - `project_reminders(id TEXT PK, project_id TEXT, fire_at INTEGER, ...)`
 *
 * Room 3.0 auto-migration detects the new table and generates
 * `CREATE TABLE project_reminders (...)` automatically. No custom SQL needed.
 *
 * No destructive changes.
 */
class Migration22To23 : AutoMigrationSpec
