package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v20 to v21 — adds `kind` column to notes.
 *
 * Stores the note kind: 'Plain' (default), 'MeetingNotes', etc.
 * Existing notes get `kind = 'Plain'` via the column default.
 * No index on `kind` is added speculatively — add one only when a
 * concrete query pattern demonstrates the need (per BAN list).
 *
 * No destructive changes: all existing rows continue to work.
 */
class Migration20To21 : AutoMigrationSpec
