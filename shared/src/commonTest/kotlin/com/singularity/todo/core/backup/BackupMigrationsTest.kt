package com.singularity.todo.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("fast")
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
        // v1→v2 fills in the fields the first version did not carry; the tasks
        // that were there are still there.
        val result = BackupMigrations.migrate(jsonObj, 1, 2)
        assertTrue(result.containsKey("tasks"))
    }

    /**
     * Every version the chain covers reaches the version this build writes.
     *
     * The chain used to skip v2 and v5: a payload written at either version
     * walked over the gap and came out the way it went in, with whatever the
     * DTO defaults happened to be. Nothing said so — the symptom was "my data
     * is wrong", not "this backup is too old".
     */
    @Test
    fun everyVersionReachesTheCurrentOne() {
        for (from in 1..BackupMigrations.CURRENT) {
            val payload = payloadAt(from)
            val result = BackupMigrations.migrate(payload, from)
            assertEquals(
                BackupMigrations.CURRENT,
                result["schemaVersion"]?.let { (it as JsonPrimitive).content?.toInt() },
                "a payload written at v$from must come out at v${BackupMigrations.CURRENT}",
            )
        }
    }

    /**
     * A step that is not registered is an error, not a no-op.
     *
     * The fold used to skip a missing step silently, so a gap in the chain
     * looked like a chain that worked.
     */
    @Test
    fun aMissingStepRaises() {
        val payload = payloadAt(1)
        assertFailsWith<IllegalStateException> {
            BackupMigrations.migrate(payload, 0)
        }
    }

    /**
     * A payload that says nothing about the agenda views says so after the
     * migration, rather than leaving the reader to know the default.
     */
    @Test
    fun theV2ToV3StepSaysWhatThePayloadDoesNotCarry() {
        val payload = payloadAt(2)
        val result = BackupMigrations.migrate(payload, 2, 3)
        assertTrue(
            result.containsKey("agendaViews"),
            "the payload that comes out of the migration must say what it does not carry",
        )
    }

    /**
     * A task that was written before the workflow state existed comes back as
     * `Open` — which is what "nothing claimed" means — and says so.
     */
    @Test
    fun theV5ToV6StepSaysWhatTheTasksDoNotCarry() {
        val payload = payloadAt(5)
        val result = BackupMigrations.migrate(payload, 5, 6)
        val task = (result["tasks"] as JsonArray).first().let {
            it as JsonObject
        }
        assertEquals(
            "Open",
            (task["workflowState"] as JsonPrimitive).content,
            "a task that carries no workflow state comes back as Open, and says so",
        )
    }

    /** A payload as it would have been written at [version]. */
    private fun payloadAt(version: Int): JsonObject {
        val jsonElement: JsonElement = json.decodeFromString(
            JsonElement.serializer(),
            """{"schemaVersion":$version,"tasks":[{"id":"t1","title":"A task","createdAt":1,"updatedAt":1}]}""",
        )
        return jsonElement as JsonObject
    }
}
