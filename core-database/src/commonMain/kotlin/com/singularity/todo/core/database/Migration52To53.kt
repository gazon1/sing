package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class Migration52To53 : Migration(52, 53) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `sync_outbox` DROP COLUMN `claimed_at`")
    }
}
