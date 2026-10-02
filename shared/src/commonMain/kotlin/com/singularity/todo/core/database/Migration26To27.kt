package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v26 to v27 — adds `estimate_minutes` to tasks and creates
 * the `time_entries` table for time tracking.
 */
class Migration26To27 : AutoMigrationSpec
