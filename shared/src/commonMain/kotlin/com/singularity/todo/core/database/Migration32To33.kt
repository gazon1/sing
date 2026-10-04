package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v32 to v33 — retry state for the sync outbox.
 *
 * Adds to `sync_outbox`:
 * - `next_attempt_at INTEGER` — earliest time a failed patch may be retried
 *
 * Adds the `sync_dead_letter` table — patches that exhausted their retries and were
 * set aside rather than dropped.
 *
 * ## Why both at once
 *
 * They are one change, not two. A backoff column with nowhere to put a patch that has
 * run out of retries is a delay, not a policy; a dead-letter store with no backoff is
 * a place to move patches that are still being retried.
 *
 * ## Why AutoMigration is enough here
 *
 * A nullable column and a new table are exactly the two shapes AutoMigration handles
 * without a hand-written spec — no column is dropped, no type is altered, and no data
 * is rewritten. Contrast [Migration31To32], which had to be manual because it dropped
 * a column that its own backfill still needed to read.
 *
 * ## Existing rows
 *
 * `next_attempt_at` is nullable and `sync_dead_letter` is empty, so every outbox row
 * that exists at v32 is immediately eligible for a push. No patch is delayed or lost
 * by upgrading.
 */
class Migration32To33 : AutoMigrationSpec
