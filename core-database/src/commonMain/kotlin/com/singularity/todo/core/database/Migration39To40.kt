package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v39 to v40 — Google Calendar sync storage.
 *
 * Adds three tables and changes no existing one:
 *
 * - `calendar_sync_state` — the incremental cursor per (user, provider, calendar)
 * - `google_event_shadow` — the last agreed field values, i.e. a merge's common ancestor
 * - `calendar_import_event` — foreign events offered to the user as tasks
 *
 * `google_event_shadow` also carries `cancelled_at`. That column was written as a separate
 * 40 -> 41 step while the sync was being built, and there is no reason to keep it: nothing
 * ever shipped the table, so no installation can be sitting at a v40 whose shadows predate
 * the column. Collapsing it makes the exported schema describe one real upgrade path instead
 * of two, one of which no user could ever take.
 *
 * ## Why v40 and not v39
 *
 * The Google tables were first written as 38 -> 39. `origin/main` independently shipped its
 * own v39 — a manual migration adding `profiles.user_id` — while this branch was open, so the
 * two could not both be v39: the same number would mean two different schemas, and a device
 * that had run one could not run the other. Upstream took v39 and this moved to 40. The
 * alternative, renumbering upstream's, would mean rewriting a migration other people had
 * already reviewed and merged.
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
 * ## Why `cancelled_at` is nullable and unbackfilled
 *
 * Cancelling an event in Google had been implemented as "delete the shadow, keep the task",
 * with a comment saying that this stops *"the next push"* from re-creating the event. There
 * was no next push at the time — the local-side write walk came later, and when it did,
 * deleting the shadow made the planner see a task with no event at all and **re-insert the
 * very event the user had just cancelled**. The comment named the failure correctly and the
 * code did not prevent it, which is the worst combination: the intent is documented and the
 * behaviour is the opposite.
 *
 * The repair is to keep the row and mark it, rather than delete it. A shadow with
 * `cancelled_at` set means "this task's event existed and the user removed it", which is
 * exactly what the push planner needs in order to leave it alone.
 *
 * `NULL` means "not cancelled", so nothing needs backfilling — and a backfill would be the
 * dangerous kind: marking every pre-existing row cancelled would make the planner skip every
 * task on the calendar, and the sync would go quiet with no error anywhere.
 *
 * One interaction with the existing sweep: `deleteNotIn` drops shadows whose event is no longer
 * in Google's listing, which would erase tombstones on the very pass that creates them. The
 * engine feeds cancelled event ids into the keep-list, so a tombstone outlives the pass that
 * wrote it and is dropped only when the account loses it entirely.
 *
 * Nothing else to specify: no existing table is altered, which is why this spec is empty.
 */
class Migration39To40 : AutoMigrationSpec
