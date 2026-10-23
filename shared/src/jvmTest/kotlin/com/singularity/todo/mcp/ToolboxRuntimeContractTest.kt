package com.singularity.todo.mcp

import com.singularity.todo.core.observability.JvmFileToolCallLog
import com.singularity.todo.core.observability.ToolCallEvent
import com.singularity.todo.core.observability.ToolCallOutcome
import com.singularity.todo.core.observability.ToolCallSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The report must read what the runtime writes.
 *
 * The registry snapshot and the call records are written by the server and read by a
 * Python script: two languages, one shape, and nothing used to say the two had to
 * agree. So they drifted — the report read the tool list as strings while the server
 * wrote objects — and the report's own tests agreed with the report, so the mismatch
 * stayed invisible until somebody read a real snapshot.
 *
 * This test closes that gap in the only way that works: it writes a snapshot and the
 * records with the *production* writers, and then runs the *production* report over
 * them and reads its answer. Both sides are the real ones, so a shape either fits or
 * it fails here.
 *
 * The test crosses a process boundary — it runs `python3` — so it is tagged `slow`.
 */
@Tag("slow")
@Tag("slow:platform")
class ToolboxRuntimeContractTest {

    private val temporaries = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaries.forEach { it.toFile().deleteRecursively() }
    }

    private fun directory(): Path {
        val dir = Files.createTempDirectory("toolbox-runtime-contract")
        temporaries.add(dir)
        return dir
    }

    private fun entry(name: String) = ToolboxToolEntry(
        name = name,
        description = "The $name tool.",
        domain = "tasks",
        readOnly = true,
        destructive = false,
        idempotent = true,
        requiredParameters = emptyList(),
        optionalParameters = emptyList(),
    )

    private fun snapshot() = ToolsetSnapshot(
        identity = ToolboxIdentity(
            buildVersion = "1.2.3",
            checkout = null,
            catalogFingerprint = "catalog0000000000",
            activeToolsetFingerprint = "active00000000000",
        ),
        registered = listOf(entry("list_tasks"), entry("create_task")),
        disabled = listOf(entry("delete_task")),
        configurationStatus = ConfigurationStatus.CURRENT,
        configurationFetchedAtEpochMillis = 1_700_000_000_000L,
        unmatchedFlags = listOf("old_tool"),
    )

    /**
     * The report's answer, as JSON.
     *
     * The report is run as a program, not imported: importing it means loading a
     * module whose name is not a valid identifier, and the report is a script with a
     * `main`, not a library.
     */
    private fun report(dataDirectory: Path): JsonObject {
        val script = repoRoot().resolve("scripts").resolve("mcp-toolbox-usage.py")
        assertTrue(script.exists(), "no usage report at $script")

        val process = ProcessBuilder(
            "python3",
            script.toString(),
            "--data-dir",
            dataDirectory.toString(),
            "--json",
        )
            .directory(repoRoot().toFile())
            .redirectErrorStream(false)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor()
        assertEquals(0, finished, "the usage report failed:\n$output")
        return Json.parseToJsonElement(output).jsonObject
    }

    @Test
    fun `the report reads the snapshot and the records the runtime wrote`() {
        val directory = directory()
        val dataDirectory = directory.resolve("tool-calls")

        RegistrySnapshot.write(snapshot(), directory.toString())

        val log = JvmFileToolCallLog(directory = dataDirectory.toString())
        // The report's window is the retention, so the records have to be *now*: a
        // fixed epoch in the past would age out of the window and the report would
        // correctly report no calls — which would say nothing about the contract.
        val now = System.currentTimeMillis()
        log.record(
            ToolCallEvent(
                ts = now,
                source = ToolCallSource.MCP.name,
                tool = "list_tasks",
                outcome = ToolCallOutcome.OK.name,
                durationMs = 12,
            ),
        )
        log.record(
            ToolCallEvent(
                ts = now + 1,
                source = ToolCallSource.MCP.name,
                tool = "list_tasks",
                outcome = ToolCallOutcome.BUSINESS_ERROR.name,
                errorCode = "validation",
                durationMs = 8,
            ),
        )

        val answer = report(dataDirectory)

        assertEquals(2, answer["records"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(0, answer["malformed"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(0, answer["unknownVersion"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(0, answer["outsideWindow"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(0, answer["registryMalformed"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertFalse(
            answer["registryUnknownVersion"]?.jsonPrimitive?.content?.toBoolean() ?: true,
            "the report did not read the snapshot the runtime wrote",
        )

        val tools = answer["tools"]?.jsonObject ?: JsonObject(emptyMap())
        val listTasks = tools["list_tasks"]?.jsonObject ?: JsonObject(emptyMap())
        assertEquals(2, listTasks["calls"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(1, listTasks["ok"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(1, listTasks["errors"]?.jsonPrimitive?.content?.toInt() ?: -1)
        assertEquals(50.0, listTasks["errorRate"]?.jsonPrimitive?.content?.toDouble() ?: -1.0)

        val registered = answer["registry"]?.jsonObject
            ?.get("registeredTools")?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("create_task", "list_tasks"), registered?.sorted() ?: emptyList())
    }

    /** The repository root, found by walking up from the working directory. */
    private fun repoRoot(): Path = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) {
        it.parent
    }
        .firstOrNull { it.resolve("scripts").resolve("mcp-toolbox-usage.py").toFile().isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
