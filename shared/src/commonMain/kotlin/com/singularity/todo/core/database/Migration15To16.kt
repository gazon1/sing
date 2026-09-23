package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v15 to v16 — adds `remote_configs` table.
 *
 * Stores the single Supabase remote configuration (URL + anon key).
 * Single-remote design from the sync-orgzly-adoption ADR.
 *
 * No destructive changes: all existing rows continue to work.
 */
class Migration15To16 : AutoMigrationSpec
