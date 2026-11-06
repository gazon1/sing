package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v25 to v26 — adds `user_id` column to `calendar_sync_task_map`.
 *
 * Fixes a critical multi-profile bug: [CalendarSyncTaskMapDao.deleteStale] was called
 * without a user filter and deleted rows from ALL profiles on a multi-profile device.
 *
 * All DAO methods are now scoped to [user_id]. The column is added as nullable TEXT
 * — existing rows get `user_id = NULL` on auto-migration, which is safe because the
 * new DAO queries include `WHERE user_id = :userId` and return empty results for
 * NULL rows (they are cleaned up as stale on the next sync pass via [deleteLegacyRows]).
 *
 * @see CalendarSyncTaskMapDao
 */
class Migration25To26 : AutoMigrationSpec
