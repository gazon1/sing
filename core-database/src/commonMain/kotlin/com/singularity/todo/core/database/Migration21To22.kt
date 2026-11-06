package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v21 to v22 — purely additive, no structural changes.
 *
 * This migration exists to maintain a continuous chain of auto-migrations
 * from v5 to the current schema version. When the schema is at v21 and
 * the app is updated to require v22, Room needs this spec to bridge the
 * gap. Room's auto-migration processor handles any actual schema diff
 * between v21.json and v22.json automatically.
 *
 * No destructive changes.
 */
class Migration21To22 : AutoMigrationSpec
