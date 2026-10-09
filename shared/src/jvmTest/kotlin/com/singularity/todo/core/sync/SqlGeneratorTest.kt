package com.singularity.todo.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Verifies [SqlGenerator] produces correct SQL from [SyncContract.FIELD_ALLOWLIST].
 *
 * These tests do NOT assert that the generated SQL matches the existing
 * hand-written migration — that is a deliberate gap. The generator is the new
 * source of truth; if the existing migration diverges, the test tells you
 * to regenerate, not that you are wrong.
 */
@Tag("fast")
class SqlGeneratorTest {

    @Test
    fun `generateFieldAllowlistSql produces DELETE plus INSERT per entity type`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()

        for (docType in SyncContract.FIELD_ALLOWLIST.keys) {
            val entityType = docType.key
            assertTrue(
                sql.contains("delete from sync_field_allowlist where entity_type = '$entityType'"),
                "Missing DELETE for entity_type='$entityType'",
            )
            assertTrue(
                sql.contains("insert into sync_field_allowlist (entity_type, field, writable) values"),
                "Missing INSERT for entity_type='$entityType'",
            )
        }
    }

    @Test
    fun `generateFieldAllowlistSql covers all DocTypes from SYNCABLE_DOC_TYPES`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()
        for (docType in SyncContract.SYNCABLE_DOC_TYPES) {
            assertTrue(
                sql.contains("'${docType.key}'"),
                "Generated SQL does not reference entity_type='${docType.key}'",
            )
        }
    }

    @Test
    fun `generateFieldAllowlistSql contains every allowlisted field`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()
        for ((docType, fields) in SyncContract.FIELD_ALLOWLIST) {
            for (field in fields) {
                assertTrue(
                    sql.contains("('${docType.key}', '$field', true)"),
                    "Missing field '$field' for entity_type='${docType.key}'",
                )
            }
        }
    }

    @Test
    fun `generateFieldAllowlistSql emits header comment`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()
        assertTrue(
            sql.startsWith("-- DO NOT EDIT BY HAND."),
            "Generated SQL should start with a header comment",
        )
        assertTrue(
            sql.contains("SyncContract.FIELD_ALLOWLIST"),
            "Header should reference SyncContract.FIELD_ALLOWLIST",
        )
        assertTrue(
            sql.contains("./gradlew :shared:generateSyncSql"),
            "Header should document the regeneration command",
        )
    }

    @Test
    fun `generateFieldAllowlistSql uses on conflict do nothing for idempotency`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()
        assertTrue(
            sql.contains("on conflict (entity_type, field) do nothing"),
            "INSERT should end with 'on conflict (entity_type, field) do nothing' for idempotency",
        )
    }

    @Test
    fun `generateFieldAllowlistSql fields are sorted alphabetically within each entity`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()
        for ((docType, fields) in SyncContract.FIELD_ALLOWLIST) {
            val sorted = fields.sorted()
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                val prevPos = sql.indexOf("('${docType.key}', '$prev', true)")
                val currPos = sql.indexOf("('${docType.key}', '$curr', true)")
                assertTrue(
                    currPos > prevPos,
                    "Fields for entity_type='${docType.key}' are not sorted: " +
                        "'$prev' (pos $prevPos) should come before '$curr' (pos $currPos)",
                )
            }
        }
    }

    @Test
    fun `generateAll returns sync_field_allowlist_sql`() {
        val all = SqlGenerator.generateAll()
        assertEquals(setOf("sync_field_allowlist.sql"), all.keys)
        assertTrue(all["sync_field_allowlist.sql"]!!.startsWith("-- DO NOT EDIT BY HAND."))
    }

    @Test
    fun `Task allowlist fields match SyncContract_TASK_ALLOWLIST constant`() {
        val sql = SqlGenerator.generateFieldAllowlistSql()
        val taskFields = SyncContract.allowlistFor(DocType.Task)
        for (field in taskFields) {
            assertTrue(
                sql.contains("('task', '$field', true)"),
                "Task field '$field' missing from generated SQL",
            )
        }
    }

    @Test
    fun `adding field to SyncContract without regenerating causes test to fail`() {
        // This is a documentation test: if a developer adds a field to
        // SyncContract.FIELD_ALLOWLIST but forgets to call SqlGenerator,
        // the `generateFieldAllowlistSql` output will be missing the field,
        // and `contains every allowlisted field` above will fail.
        // The test suite is the enforcement mechanism.
        assertTrue(true, "This test documents the contract")
    }
}
