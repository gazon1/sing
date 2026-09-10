package com.singularity.todo.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMigrationsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun migrateIdentityWhenFromEqualsTo() {
        val original = """{"tasks":[],"schemaVersion":1}"""
        val jsonElement: JsonElement = json.decodeFromString(JsonElement.serializer(), original)
        val jsonObj: JsonObject = jsonElement as JsonObject
        val result = BackupMigrations.migrate(jsonObj, 1, 1)
        assertTrue(result.containsKey("tasks"))
    }

    @Test
    fun migrateEmptyChainReturnsIdentity() {
        val original = """{"tasks":[]}"""
        val jsonElement: JsonElement = json.decodeFromString(JsonElement.serializer(), original)
        val jsonObj: JsonObject = jsonElement as JsonObject
        // No migrations registered, so v1→v2 returns identity
        val result = BackupMigrations.migrate(jsonObj, 1, 2)
        assertTrue(result.containsKey("tasks"))
    }
}
