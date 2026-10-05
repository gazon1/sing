package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v38 to v39 — Google Calendar sync storage.
 *
 * Adds three tables and changes no existing one:
 *
 * - `calendar_sync_state` — the incremental cursor per (user, provider, calendar)
 * - `google_event_shadow` — the last agreed field values, i.e. a merge's common ancestor
 * - `calendar_import_event` — foreign events offered to the user as tasks
 *
 * ## Why this is a pure addition
 *
 * The obvious implementation would have retyped `calendar_sync_task_map.event_id` from
 * `INTEGER` to `TEXT`, because Google event ids are opaque strings. That was rejected: a
 * column type change with real rows behind it is the one kind of migration that can lose
 * data, and Google state does not need to share a table with the device-calendar path.
 * The two providers have different shapes, different cursors and different lifecycles, so
 * they get separate tables and the existing one is left exactly as it was.
 *
 * ## Why `user_id` is in every primary key
 *
 * The existing tables key on `id` alone, and that is sound: ids are ULIDs
 * (`core/ids/IdGen.kt`), 80 bits of randomness, already a global namespace. A Google
 * event or calendar id is an opaque string scoped to one account, and two profiles on one
 * device can hold the *same* one. So these new tables — the ones keyed by a remote
 * identifier — put `user_id` in the key. See the two-port ADR.
 *
 * Nothing to specify: no existing table is altered, which is why this spec is empty.
 */
class Migration38To39 : AutoMigrationSpec
