package com.singularity.todo.core.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer's vocabulary is the policy's vocabulary.
 *
 * The records outlive the code that wrote them, and the report reads them by the
 * vocabulary in `config/docs/tool-call-records.json`. That only works if the two
 * agree — and the disagreement is invisible until somebody reads a real record: the
 * report once expected `ok` where the writer writes `OK`, and counted every real
 * call as an error.
 *
 * So the policy is the declaration, the Kotlin enums are checked against it, and the
 * Python reader reads the same file. One fact, two readers, and a test that says so.
 */
@Tag("fast")
class ToolCallRecordPolicyTest {

    private val policy: JsonObject by lazy {
        val file = File(repoRoot(), "config/docs/tool-call-records.json")
        assertTrue(file.isFile, "no record policy at $file")
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `the outcomes the writer can produce are the outcomes the policy declares`() {
        val declared = policy["outcomes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            ?: error("the policy declares no outcomes")

        assertEquals(
            declared,
            ToolCallOutcome.entries.map { it.name }.toSet(),
            "the writer can produce an outcome the policy does not declare, and the " +
                "reader would then have to guess what it means",
        )
    }

    @Test
    fun `the sources the writer can produce are the sources the policy declares`() {
        val declared = policy["sources"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            ?: error("the policy declares no sources")

        assertEquals(
            declared,
            ToolCallSource.entries.map { it.name }.toSet(),
            "the writer can produce a source the policy does not declare",
        )
    }

    @Test
    fun `the record's version is the version the policy declares`() {
        val declared = policy["schemaVersion"]?.jsonPrimitive?.content?.toInt()
            ?: error("the policy declares no schema version")

        assertEquals(
            declared,
            ToolCallEvent.SCHEMA_VERSION,
            "the writer stamps a version the policy does not know, and the reader " +
                "would count every record of it as unreadable",
        )
    }

    @Test
    fun `the retention is the retention the policy declares`() {
        val declared = policy["retentionDays"]?.jsonPrimitive?.content?.toInt()
            ?: error("the policy declares no retention")

        assertEquals(
            declared,
            JvmFileToolCallLog.RETENTION_DAYS,
            "the writer deletes records on a different schedule than the policy says, " +
                "so the window the report reports over is not the window the data lives in",
        )
    }

    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }
        .firstOrNull { File(it, "config/docs/tool-call-records.json").isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
