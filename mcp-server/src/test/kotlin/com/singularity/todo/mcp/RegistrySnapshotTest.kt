package com.singularity.todo.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry snapshot: what the toolbox looked like when the server started.
 *
 * The file is read by the local report (`scripts/mcp-toolbox-usage.py`), so its shape is
 * a contract between two languages. This test pins the Kotlin side of it; the report's
 * own tests pin the Python side, and both use the shape the server actually writes.
 */
@Tag("fast")
class RegistrySnapshotTest {

    private val temporaries = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaries.forEach { it.toFile().deleteRecursively() }
    }

    private fun directory(): Path {
        val dir = Files.createTempDirectory("registry-snapshot")
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

    private fun snapshot(
        registered: List<ToolboxToolEntry> = listOf(entry("list_tasks")),
        disabled: List<ToolboxToolEntry> = emptyList(),
        unmatched: List<String> = emptyList(),
    ) = ToolsetSnapshot(
        identity = ToolboxIdentity(
            buildVersion = "1.2.3",
            checkout = null,
            catalogFingerprint = "catalog0000000000",
            activeToolsetFingerprint = "active00000000000",
        ),
        registered = registered,
        disabled = disabled,
        configurationStatus = ConfigurationStatus.CURRENT,
        configurationFetchedAtEpochMillis = 1_700_000_000_000L,
        unmatchedFlags = unmatched,
    )

    private fun written(directory: Path, snapshot: ToolsetSnapshot): JsonObject {
        RegistrySnapshot.write(snapshot, directory.toString())
        val file = directory.resolve("tool-calls").resolve("registry.json")
        assertTrue(file.exists(), "the registry snapshot was not written to $file")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `the snapshot names the registered and the disabled tools`() {
        val directory = directory()

        val payload = written(directory, snapshot(disabled = listOf(entry("delete_task"))))

        val registered = payload["registeredTools"]?.jsonArray
            ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        val disabled = payload["disabledTools"]?.jsonArray
            ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("list_tasks"), registered)
        assertEquals(listOf("delete_task"), disabled)
    }

    @Test
    fun `the snapshot carries the identity and the configuration status`() {
        val directory = directory()

        val payload = written(directory, snapshot())

        assertEquals("1.2.3", payload["buildVersion"]?.jsonPrimitive?.content)
        assertEquals("catalog0000000000", payload["catalogFingerprint"]?.jsonPrimitive?.content)
        assertEquals("active00000000000", payload["activeToolsetFingerprint"]?.jsonPrimitive?.content)
        assertEquals("current", payload["configurationStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the snapshot names the flags that matched no tool`() {
        val directory = directory()

        val payload = written(directory, snapshot(unmatched = listOf("old_tool")))

        val flags = payload["unmatchedFlags"]?.jsonArray?.map { it.jsonPrimitive?.content }
        assertEquals(
            listOf("old_tool"),
            flags,
            "the report reads this list: it is how an operator learns that a flag they " +
                "set switched nothing off",
        )
    }
}
