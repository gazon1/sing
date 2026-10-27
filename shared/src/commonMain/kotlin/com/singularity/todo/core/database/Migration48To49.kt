package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class Migration48To49 : Migration(48, 49) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `sync_outbox` ADD COLUMN `profile_id` TEXT NOT NULL DEFAULT ''",
        )
    }
}