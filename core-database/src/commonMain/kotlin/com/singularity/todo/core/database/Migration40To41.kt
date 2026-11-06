package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v40 to v41 — the attachment sync preference.
 *
 * Adds one column, `sync_state.attachments_sync_enabled`, defaulting to `0`.
 *
 * ## Why a column and not a `SyncPrefs` key
 *
 * `SyncPrefs` is the one remaining global settings slot, shared by every profile on the
 * device, and its setters are marked deprecated in favour of the scoped repository for
 * exactly that reason. A third global key for "sync my attachments" would let a profile
 * inherit another profile's answer about files that belong to it.
 *
 * ## Why the default is off
 *
 * There is no binary transport yet (`ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE`). Defaulting to
 * `true` would make every existing scope claim a preference it never made and that no
 * code could honour, so an upgrade would silently record a promise the client cannot
 * keep. `0` is the only value that is truthful on the day the column appears.
 *
 * ## Why nothing else changes
 *
 * No existing column is touched, no table is added or dropped, and no data is rewritten,
 * which is why this spec is empty. Room derives the `ALTER TABLE ADD COLUMN` itself.
 */
class Migration40To41 : AutoMigrationSpec
