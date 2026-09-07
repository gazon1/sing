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
