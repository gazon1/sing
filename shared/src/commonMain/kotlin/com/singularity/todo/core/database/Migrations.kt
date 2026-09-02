package com.singularity.todo.core.database

import androidx.room.migration.AutoMigrationSpec

/**
 * Migration from v1 to v2 — adds sync columns to all entities.
 */
object Migration1To2 : AutoMigrationSpec {
    // Room will auto-detect schema changes for v2
}
