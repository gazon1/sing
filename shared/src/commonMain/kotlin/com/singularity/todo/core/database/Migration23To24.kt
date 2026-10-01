package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v23 to v24 — adds `task_id` column to notes for structural
 * note-to-task linkage (replacing the `task://` URL token inside `outgoing_links`).
 *
 * The column is nullable with no backfill: existing notes keep `task_id = NULL`.
 * An index is added to enable O(log n) lookups instead of O(n) LIKE scans.
 *
 * The existing `outgoing_links` JSON column is **not** modified — it still carries
 * `note://` tokens for note-to-note backlinks and `task://` tokens as a
 * backward-compatibility bridge during the migration window.
 *
 * @see com.singularity.todo.feature.notes.NotesRepository.watchForTask
 */
class Migration23To24 : AutoMigrationSpec
