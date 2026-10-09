package com.singularity.todo.core.sync

import kotlin.io.path.Path
import kotlin.io.path.writeText

/**
 * Generates server-side SQL artifacts from [SyncContract.FIELD_ALLOWLIST].
 *
 * Call [generate] (or individual `generate*` functions) to produce SQL text,
 * then write it to the desired output path.
 *
 * ## Artifacts
 *
 * ### `sync_field_allowlist.sql`
 *
 * ```sql
 * -- DO NOT EDIT BY HAND.  Generated from SyncContract.FIELD_ALLOWLIST.
 * -- Run `./gradlew :shared:generateSyncSql` to regenerate.
 * ...
 * ```
 *
 * Uses `DELETE + INSERT` so re-applying is idempotent (removing a field
 * from the allowlist produces a `delete` that removes it, and re-apply
 * brings it back — the developer must also remove it from the Kotlin entity
 * to actually fix the drift).
 *
 * Each entity's rows are emitted in alphabetical order by field name.
 * Seven value tuples per line — matching the style of the hand-written
 * seed in `2026-10-07-sync_schema.sql`.
 *
 * ## Usage
 *
 * ```kotlin
 * val sql = SqlGenerator.generateAll()
 * Path("supabase/migrations/9999-01-01-sync_field_allowlist_generated.sql")
 *     .writeText(sql)
 * ```
 */
object SqlGenerator {

    /**
     * Generates the complete `sync_field_allowlist.sql` artifact.
     *
     * Format: one `DELETE + INSERT` block per entity type, fields sorted
     * alphabetically, 7 value tuples per line.
     */
    fun generateFieldAllowlistSql(): String {
        val header = buildString {
            appendLine("-- DO NOT EDIT BY HAND.  Generated from SyncContract.FIELD_ALLOWLIST.")
            appendLine("-- Run `./gradlew :shared:generateSyncSql` to regenerate.")
            appendLine()
            appendLine("-- Idempotent: DELETE + INSERT replaces all rows for each entity type.")
            appendLine("-- To remove a field, remove it from SyncContract.FIELD_ALLOWLIST first,")
            appendLine("-- then regenerate this file.")
            appendLine()
        }

        val blocks = SyncContract.FIELD_ALLOWLIST.entries
            .sortedBy { it.key.ordinal }
            .joinToString("\n") { (docType, fields) ->
                val sorted = fields.sorted()
                val rows = sorted.chunked(7) { chunk ->
                    chunk.joinToString(", ") { field ->
                        "('${docType.key}', '$field', true)"
                    }
                }.joinToString(",\n    ")

                buildString {
                    appendLine("-- ${docType.key}")
                    appendLine("delete from sync_field_allowlist where entity_type = '${docType.key}';")
                    append("insert into sync_field_allowlist (entity_type, field, writable) values")
                    if (sorted.size <= 7) {
                        append(" $rows;\n    on conflict (entity_type, field) do nothing;")
                    } else {
                        appendLine()
                        appendLine("    $rows;")
                        append("    on conflict (entity_type, field) do nothing;")
                    }
                }
            }

        return header + blocks + "\n"
    }

    /**
     * Generates all SQL artifacts and returns them as a map of filename → content.
     */
    fun generateAll(): Map<String, String> = mapOf(
        "sync_field_allowlist.sql" to generateFieldAllowlistSql(),
    )

    /**
     * Writes all generated SQL artifacts to [outputDir].
     *
     * @param outputDir Directory to write files into (e.g. `supabase/migrations/`).
     *                  Files are overwritten if they exist.
     */
    fun writeAll(outputDir: String) {
        for ((filename, content) in generateAll()) {
            Path(outputDir, filename).writeText(content)
        }
    }
}
