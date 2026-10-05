package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v36 to v37 — the server's row version, per shadowed entity.
 *
 * Adds `sync_shadow.server_version`, which records what the server last said a row's
 * version was. The client parsed that number on every push and then dropped it, so
 * every patch it ever sent carried `baseVersion = 0` — a claim that the server had
 * never seen the row, on every patch including the tenth edit to a row the server had
 * been applying for days.
 *
 * ## Why the new column defaults to 0
 *
 * 0 is the honest reading for every existing row: the client never kept the version, so
 * what it knows is nothing. Defaulting to anything else would be inventing a fact, and
 * a wrong base version is worse than a missing one — the server answers a stale or
 * non-zero base with a refusal, and the change is then parked rather than applied.
 *
 * The cost is one round of patches at `baseVersion = 0` after the upgrade, which the
 * server answers as it always did for a row it holds. That is the same request this
 * build has been making for the whole of its life.
 */
class Migration36To37 : AutoMigrationSpec
