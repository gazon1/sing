package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v1 to v2 — adds sync columns to all entities.
 */
object Migration1To2 : AutoMigrationSpec {
    // Room will auto-detect schema changes for v2
}

/**
 * Migration from v2 to v3 — adds attachments table.
 */
object Migration2To3 : AutoMigrationSpec {
    // Room will auto-detect schema changes for v3
}

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
