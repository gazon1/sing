package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v5 to v6 — adds pinned, color, sort_order, word_count, char_count
 * columns to notes. Registered in [AppDatabase] only when leaving dev mode.
 * During development uses [fallbackToDestructiveMigration] so no migration runs.
 */
class Migration5To6 : AutoMigrationSpec

/**
 * Migration from v6 to v7 — adds outgoing_links column to notes for wikilink backlinks.
 */
class Migration6To7 : AutoMigrationSpec

/**
 * Migration from v7 to v8 — adds llm_usage table for token observability.
 */
class Migration7To8 : AutoMigrationSpec

/**
 * Migration from v8 to v9 — adds profiles table for multi-profile support.
 */
class Migration8To9 : AutoMigrationSpec

/**
 * Migration from v9 to v10 — adds parent_task_id column to the tasks table for
 * MCP-driven decompose-and-create workflows.
 *
 * Adding a nullable column with no default is a safe auto-migration: existing
 * rows continue to read back with `parentTaskId == null`. Sub-task creation
 * writes the value explicitly.
 */
class Migration9To10 : AutoMigrationSpec

/**
 * Migration from v10 to v11 — projects table schema changes:
 * 1. Adds `idempotency_key TEXT UNIQUE` (random UUID, for MCP idempotent create)
 * 2. Removes `is_notebook` (legacy dead column, replaced by kind-based differentiation)
 *
 * Room KSP auto-infers both changes from the schema diff (10.json → 11.json):
 * - @DeleteColumn drops is_notebook
 * - new idempotency_key column is auto-detected as ADD COLUMN
 * No migrate() override needed.
 */
@androidx.room3.DeleteColumn(tableName = "projects", columnName = "is_notebook")
class Migration10To11 : AutoMigrationSpec

/**
 * Migration from v11 to v12 — adds agenda_views table for saved agenda view persistence.
 *
 * All-in-blob storage: id, user_id, name, sections_json (whole AgendaDefinition as JSON),
 * created_at, updated_at. Composite PK (id, user_id) for per-profile isolation.
 */
class Migration11To12 : AutoMigrationSpec

/**
 * Migration from v12 to v13 — adds nullable `view_id` column to task_reminders.
 *
 * When a reminder is created from within a saved agenda view, this column stores
 * the [SavedAgendaViewId], enabling the notification tap deeplink to open
 * directly into that view. Null for reminders created outside a saved view.
 *
 * Adding a nullable column with no default is a safe auto-migration: existing
 * rows read back with `viewId == null`.
 */
class Migration12To13 : AutoMigrationSpec

/**
 * Migration from v13 to v14 — adds `task_dependencies` join table for
 * task dependency tracking (blocked / blocking).
 *
 * Tables added:
 * - `task_dependencies(task_id, depends_on_task_id)` — composite PK, two indices.
 *
 * No columns are added to the `tasks` table; dependencies are stored in a
 * separate join table (many-to-many, same pattern as `task_tags`).
 *
 * @see com.singularity.todo.docs.decisions.2026-09-18-task-dependencies
 */
class Migration13To14 : AutoMigrationSpec

/**
 * Migration from v14 to v15 — adds nullable `last_fired_at` column to `task_reminders`.
 *
 * Records the epoch millis of the last successful fire for recurring reminders,
 * enabling the [ReminderScheduler][com.singularity.todo.feature.reminders.ReminderScheduler]
 * to skip a recurring reminder if it fired recently (within [MIN_RECURRING_INTERVAL_MS]).
 *
 * Null for:
 * - one-shot reminders that have never fired
 * - pre-existing rows (added by this migration)
 *
 * Adding a nullable column with no default is a safe auto-migration: existing
 * rows read back with `lastFiredAt == null`.
 */
class Migration14To15 : AutoMigrationSpec

/**
 * Migration from v16 to v17 — adds `calendar_sync_task_map` table with `checksum` column.
 *
 * Stores the mapping from local task IDs to system-calendar event IDs,
 * enabling the sync worker to update/delete existing events rather than
 * re-inserting on every sync.
 *
 * Tables added:
 * - `calendar_sync_task_map(task_id TEXT PRIMARY KEY, calendar_id TEXT,
 *                            event_id INTEGER, synced_at INTEGER, checksum INTEGER)`
 *
 * Room 3.0 auto-migration detects the new `checksum` field in the entity and
 * generates `ALTER TABLE calendar_sync_task_map ADD COLUMN checksum INTEGER NOT NULL DEFAULT 0`
 * automatically. No custom SQL is needed.
 */
class Migration16To17 : AutoMigrationSpec

// Migration17To18 is defined in Migration17To18.kt
// Migration18To19 is defined in Migration18To19.kt
