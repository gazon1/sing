package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v55 to v56 — built-in chat transcript persistence.
 *
 * Adds two tables:
 * - `chat_conversations` — conversation metadata scoped by user/profile id.
 * - `chat_messages` — ordered transcript messages with assistant lifecycle status.
 *
 * Room derives the DDL and indices from the entities. No data backfill is needed:
 * previous chat state lived only in memory.
 */
class Migration55To56 : AutoMigrationSpec
