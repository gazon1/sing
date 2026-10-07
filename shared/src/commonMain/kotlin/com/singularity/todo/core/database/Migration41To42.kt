package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v41 to v42 — the attachment annotation table.
 *
 * Adds one table, `attachment_annotations`, with its three indices.
 *
 * ## Why a new table and not columns on `attachments`
 *
 * A note is a first-class row: it has its own id, its own timestamps, its own soft delete
 * and — later — its own place in a sync document. Storing it as a serialised blob on the
 * attachment would make every annotation a read-modify-write of the whole list, would give
 * it no identity to delete, and would put notes written against *different spans of the
 * same file* in the same row, which is the case the feature exists for.
 *
 * ## Why no data is moved
 *
 * An upgrade cannot invent notes: there is nothing on disk yet that describes one. Room
 * derives the `CREATE TABLE` and the indices from the entity, which is why this spec is
 * empty.
 */
class Migration41To42 : AutoMigrationSpec
