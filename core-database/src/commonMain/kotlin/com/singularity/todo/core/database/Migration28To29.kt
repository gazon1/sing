package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v28 to v29 — creates the `ai_proposal_item` table.
 *
 * The unique index on `(proposal_id, fingerprint)` is what stops a re-run of the same
 * generation from showing the user the same change twice within one card.
 */
class Migration28To29 : AutoMigrationSpec
