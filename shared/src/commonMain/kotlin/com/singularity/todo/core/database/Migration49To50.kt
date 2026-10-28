package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class Migration49To50 : Migration(49, 50) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `sync_outbox` ADD COLUMN `claimed_at` INTEGER",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_owner_id_profile_id_next_attempt_at_created_at` " +
                "ON `sync_outbox` (`owner_id`, `profile_id`, `next_attempt_at`, `created_at`)",
        )
    }
}
