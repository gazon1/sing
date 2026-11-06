package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v34 to v35 — the shadow store.
 *
 * Adds `sync_shadow`, one row per entity per scope, holding the state the server
 * is known to hold and the state a queued patch will bring it to.
 *
 * ## Why the rows are not backfilled
 *
 * A backfill would have to claim the server already holds the local state of every
 * entity, and the honest answer is "unknown, because nothing has been uploaded
 * yet". Writing that claim would make the first edit after the upgrade produce an
 * empty diff — the field changes, the server never hears about it, and the entity
 * looks synced forever.
 *
 * So the table starts empty. An entity with no shadow is an entity that has never
 * been uploaded, and its first patch carries every field, which is the truth.
 */
class Migration34To35 : AutoMigrationSpec
