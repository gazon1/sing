package com.singularity.todo.core.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android writer keeps records for as long as the policy says.
 *
 * The policy in `config/docs/tool-call-records.json` is what the report reads to
 * decide its window. If this writer deleted on a different schedule, the report's
 * window would be a span in which some records exist and others do not — and the
 * report would be counting a bound it had no way to name.
 *
 * The JVM writer's side of the same question is in `ToolCallRecordPolicyTest`.
 */
@Tag("fast")
class AndroidRecordRetentionTest {

    @Test
    fun `the retention is the retention the policy declares`() {
        val file = File(repoRoot(), "config/docs/tool-call-records.json")
        assertTrue(file.isFile, "no record policy at $file")
        val policy = Json.parseToJsonElement(file.readText()).jsonObject
        val declared = policy["retentionDays"]?.jsonPrimitive?.content?.toInt()
            ?: error("the policy declares no retention")

        assertEquals(
            declared,
            AndroidFileToolCallLog.RETENTION_DAYS,
            "the Android writer deletes records on a different schedule than the policy " +
                "says, so the window the report reports over is not the window the " +
                "data lives in",
        )
    }

    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }
        .firstOrNull { File(it, "config/docs/tool-call-records.json").isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
