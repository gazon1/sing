package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v18 to v19 — adds `remote_config_cache` table.
 *
 * Stores the last-known-good [com.singularity.todo.core.config.RemoteConfigSnapshot]
 * as a JSON blob, enabling offline operation when the remote config endpoint is
 * unreachable and TTL has not yet elapsed.
 *
 * Single-row cache: the row with `id = "default"` holds the last successfully
 * fetched snapshot. Stored as JSON so adding new fields to [RemoteConfigSnapshot]
 * does not require another schema migration (forward-compat via
 * [kotlinx.serialization.json.Json.ignoreUnknownKeys]).
 *
 * No destructive changes.
 */
class Migration18To19 : AutoMigrationSpec
