package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v15 to v16 — adds `remote_configs` table.
 *
 * Stores the single Supabase remote configuration (URL + anon key).
 * One row, not one per profile: the remote config is a property of the backend,
 * and a per-profile copy would let two clients disagree about which backend is
 * authoritative. The row is pinned to `id = "default"`.
 *
 * No destructive changes: all existing rows continue to work.
 *
 * Verified against `shared/schemas/…/16.json`, which is Room's own export and the
 * authority here. This KDoc previously claimed `saved_searches` — a copy of
 * [Migration17To18], which is the migration that does add that table. A migration
 * note is not self-checking, and a wrong one sends the next reader to the wrong
 * table; the exported schema is what makes the claim falsifiable.
 */
class Migration15To16 : AutoMigrationSpec
