package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v39 to v40 — remember that a Google event was cancelled.
 *
 * Adds one nullable column, `google_event_shadow.cancelled_at`, and changes nothing else.
 *
 * ## Why a tombstone instead of deleting the row
 *
 * Cancelling an event in Google was implemented as "delete the shadow, keep the task", with
 * a comment saying that this stops *"the next push"* from re-creating the event. There was no
 * next push at the time — the local-side write walk came later, and when it did, deleting the
 * shadow made the planner see a task with no event at all and **re-insert the very event the
 * user had just cancelled**. The comment named the failure correctly and the code did not
 * prevent it, which is the worst combination: the intent is documented and the behaviour is
 * the opposite.
 *
 * The repair is to keep the row and mark it, rather than delete it. A shadow with
 * `cancelled_at` set means "this task's event existed and the user removed it", which is
 * exactly what the push planner needs in order to leave it alone.
 *
 * ## Why nullable and nullable-when-absent
 *
 * `NULL` means "not cancelled" and therefore means every row written before this migration,
 * with no backfill. A `NOT NULL DEFAULT 0` would be equally correct but would have to rewrite
 * every existing row to express what `NULL` already says.
 *
 * The one interaction with the existing sweep: `deleteNotIn` drops shadows whose event is no
 * longer in Google's listing, which would erase tombstones on the very pass that creates them.
 * The engine now feeds cancelled event ids into the keep-list, so a tombstone outlives the
 * pass that wrote it and is dropped only when the account loses it entirely.
 */
class Migration39To40 : AutoMigrationSpec