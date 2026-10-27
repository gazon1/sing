package com.singularity.todo.mcp

import com.singularity.todo.feature.ai.McpToolCatalog
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shape of `describe_toolbox`, which is a contract with every client.
 *
 * The payload is what an agent parses when a tool is missing or behaves unexpectedly.
 * A renamed key or a dropped field does not fail anywhere in the server — it fails in
 * the client, quietly, and the agent concludes that a tool does not exist. So the shape
 * is pinned here, key by key.
 */
@Tag("fast")
class ToolboxToolTest {

    private fun entry(name: String, domain: String = "tasks") = ToolboxToolEntry(
        name = name,
        description = "The $name tool.",
        domain = domain,
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
            checkout = CheckoutInfo(sha = "abc123", dirty = false),
            catalogFingerprint = "catalog0000000000",
            activeToolsetFingerprint = "active00000000000",
        ),
        registered = registered,
        disabled = disabled,
        configurationStatus = ConfigurationStatus.CURRENT,
        configurationFetchedAtEpochMillis = 1_700_000_000_000L,
        unmatchedFlags = unmatched,
    )

    @Test
    fun `an argument that is absent, null or not a primitive reads as no argument`() {
        assertNull(null.argumentString(), "absent")
        assertNull(JsonNull.argumentString(), "JSON null must not read as the string \"null\"")
        assertNull(buildJsonObject { put("a", 1) }.argumentString(), "an object is not an argument")
        assertNull(buildJsonArray { add("a") }.argumentString(), "an array is not an argument")
    }

    @Test
    fun `a primitive argument reads as its text`() {
        assertEquals("tasks", JsonPrimitive("tasks").argumentString())
        assertEquals("true", JsonPrimitive(true).argumentString())
        assertEquals(true, JsonPrimitive(true).argumentString()?.toBooleanStrictOrNull())
    }

    @Test
    fun `the toolset carries the five identity facts and the count`() {
        val payload = ToolboxTool(snapshot()).payload(listOf(entry("list_tasks")), emptyList(), false)

        val toolset = payload["toolset"]?.jsonObject ?: error("no toolset")
        assertEquals("1.2.3", toolset["buildVersion"]?.jsonPrimitive?.content)
        assertEquals("catalog0000000000", toolset["catalogFingerprint"]?.jsonPrimitive?.content)
        assertEquals("active00000000000", toolset["activeToolsetFingerprint"]?.jsonPrimitive?.content)
        assertEquals("current", toolset["configurationStatus"]?.jsonPrimitive?.content)
        assertEquals(1, toolset["registeredCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `the toolset says which contract version it speaks`() {
        """An agent that cannot tell a newer server from an older one reads a
        field it does not understand as if it did."""
        val payload = ToolboxTool(snapshot()).payload(listOf(entry("list_tasks")), emptyList(), false)

        val toolset = payload["toolset"]?.jsonObject ?: error("no toolset")
        assertEquals(
            McpToolCatalog.SURFACE_CONTRACT_VERSION,
            toolset["surfaceContractVersion"]?.jsonPrimitive?.int,
        )
    }

    @Test
    fun `the checkout is reported when there is one`() {
        val payload = ToolboxTool(snapshot()).payload(listOf(entry("list_tasks")), emptyList(), false)

        val checkout = payload["checkout"]?.jsonObject ?: error("no checkout")
        assertEquals("abc123", checkout["sha"]?.jsonPrimitive?.content)
        assertFalse(checkout["dirty"]?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `a tool is reported with its own description`() {
        val payload = ToolboxTool(snapshot())
            .payload(listOf(entry("list_tasks", domain = "tasks")), emptyList(), false)

        val domains = payload["toolset"]?.jsonObject?.get("domains")?.jsonObject
            ?: error("no domains")
        val tools = domains["tasks"]?.jsonObject?.get("tools")?.jsonArray ?: error("no tools")
        val tool = tools.first().jsonObject
        assertEquals("list_tasks", tool["name"]?.jsonPrimitive?.content)
        assertEquals(
            "The list_tasks tool.",
            tool["description"]?.jsonPrimitive?.content,
            "the description is the tool class's own text — the same string the schema carries",
        )
    }

    @Test
    fun `the disabled list is only reported when it is asked for`() {
        val disabled = listOf(entry("delete_task"))
        val tool = ToolboxTool(snapshot(disabled = disabled))

        val without = tool.payload(listOf(entry("list_tasks")), disabled, false)
        assertFalse(
            "disabled" in without,
            "the disabled list is a diagnostic, not the default answer",
        )

        val with = tool.payload(listOf(entry("list_tasks")), disabled, true)
        val names = with["disabled"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("delete_task"), names)
    }

    @Test
    fun `a long disabled list is truncated and says so`() {
        val disabled = (1..25).map { entry("tool_$it") }
        val tool = ToolboxTool(snapshot(disabled = disabled))

        val payload = tool.payload(listOf(entry("list_tasks")), disabled, true)

        assertEquals(ToolboxTool.MAX_DISABLED_LISTED, payload["disabled"]?.jsonArray?.size)
        assertTrue(payload["disabledTruncated"]?.jsonPrimitive?.boolean == true)
        assertEquals(25, payload["disabledCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a flag that named no tool is reported`() {
        val tool = ToolboxTool(snapshot(unmatched = listOf("old_tool")))

        val payload = tool.payload(listOf(entry("list_tasks")), emptyList(), false)

        val flags = payload["unmatchedFlags"]?.jsonArray
            ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("old_tool"), flags)
    }

    @Test
    fun `a build with no unmatched flag says nothing about unmatched flags`() {
        val tool = ToolboxTool(snapshot())

        val payload = tool.payload(listOf(entry("list_tasks")), emptyList(), false)

        assertFalse("unmatchedFlags" in payload, "an empty list of problems is not a problem")
    }
}
