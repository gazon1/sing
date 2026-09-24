package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v19 to v20 — adds `recurrence_rule` column to tasks table.
 *
 * Stores the JSON-serialized [com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec]
 * for recurring tasks. Null means the task is non-recurring.
 *
 * Index on `recurrence_rule` enables efficient queries for "all recurring tasks".
 *
 * No destructive changes.
 */
class Migration19To20 : AutoMigrationSpec
