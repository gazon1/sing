package com.singularity.todo.mcp

import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.systemTimeZone
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.feature.ai.tools.AdrStorage
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The three documents the server serves, and the rules they obey.
 *
 * A resource is a *document*: the client asks for `singularity://decision/<slug>` and
 * gets the decision. The rules that matter are the access ones — a slug is resolved
 * through the corpus, never concatenated into a path, and an archived or ignored file
 * must not be reachable by guessing its name.
 *
 * The corpus here is a real one on disk, written into a temporary directory, because
 * the rules are about *paths*.
 */
@Tag("fast")
class ToolboxResourcesTest {

    private val temporaries = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaries.forEach { root ->
            root.toFile().deleteRecursively()
        }
    }

    /** A checkout with two decisions, one archived decision and one backlog entry. */
    private fun corpus(): Path {
        val root = Files.createTempDirectory("toolbox-resources")
        temporaries.add(root)
        val decisions = root.resolve("docs/decisions")
        decisions.createDirectories()
        decisions.resolve("2026-10-01-first.md").writeText(
            """
            ---
            title: "The first decision"
            date: 2026-10-01
            status: accepted
            tags: ["one"]
            ---

            The body of the first decision.
            """.trimIndent(),
        )
        decisions.resolve("2026-10-02-second.md").writeText(
            """
            ---
            title: "The second decision"
            date: 2026-10-02
            status: open
            tags: ["two"]
            ---

            The body of the second decision.
            """.trimIndent(),
        )
        // The archive is not a decision, and it must not be reachable by name.
        val archived = decisions.resolve("archive")
        archived.createDirectories()
        archived.resolve("2026-09-01-archived.md").writeText(
            """
            ---
            title: "The archived decision"
            date: 2026-09-01
            status: archived
            tags: []
            ---

            The body of the archived decision.
            """.trimIndent(),
        )
        // The backlog is not a decision either.
        val deferred = decisions.resolve("deferred")
        deferred.createDirectories()
        deferred.resolve("2026-10-03-planned.md").writeText(
            """
            ---
            title: "The planned change"
            date: 2026-10-03
            status: open
            tags: []
            ---

            The body of the planned change.
            """.trimIndent(),
        )
        return root
    }

    private fun storage(root: Path): AdrStorage = AdrStorage(
        host = object : HostEnvironmentPort {
            override fun workingDirectory(): String = root.toString()
            override fun homeDirectory(): String = root.toString()
            override fun applicationDataDirectory(): String = root.resolve("data").toString()
        },
        clock = Clock.System,
        timeZone = systemTimeZone,
    )

    private fun snapshot() = ToolsetSnapshot(
        identity = ToolboxIdentity(
            buildVersion = "1.2.3",
            checkout = null,
            catalogFingerprint = "catalog0000000000",
            activeToolsetFingerprint = "active00000000000",
        ),
        registered = listOf(
            ToolboxToolEntry(
                name = "list_tasks",
                description = "The list_tasks tool.",
                domain = "tasks",
                readOnly = true,
                destructive = false,
                idempotent = true,
                requiredParameters = emptyList(),
                optionalParameters = emptyList(),
            ),
        ),
        disabled = emptyList(),
        configurationStatus = ConfigurationStatus.CURRENT,
        configurationFetchedAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun `the resource uris are the contract they have always been`() {
        // A URI is what a client bookmarks, subscribes to, or asks for by name. A
        // rename of any of them breaks every client that used it, and nothing else in
        // the tree would say so: the payloads are pinned by the tests below, but the
        // names were only written down in the documentation.
        assertEquals("singularity://toolbox", ToolboxResources.TOOLBOX_URI)
        assertEquals("singularity://decisions", ToolboxResources.DECISIONS_URI)
        assertEquals("singularity://decision", ToolboxResources.DECISION_URI)
    }

    @Test
    fun `the toolbox document lists the registered tools`() {
        val resources = ToolboxResources(snapshot(), null)

        val payload = resources.toolboxPayload()

        assertEquals("1.2.3", payload["buildVersion"]?.jsonPrimitive?.content)
        val tools = payload["tools"]?.jsonArray ?: error("no tools")
        val first = tools.first().jsonObject
        assertEquals("list_tasks", first["name"]?.jsonPrimitive?.content)
        assertEquals("tasks", first["domain"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the toolbox document says which contract version it speaks`() {
        """A client that reads the resource rather than the tool needs to know the
        same thing: whether the shape it knows is the shape this server speaks."""
        val resources = ToolboxResources(snapshot(), null)

        val payload = resources.toolboxPayload()

        assertEquals(
            McpToolCatalog.SURFACE_CONTRACT_VERSION,
            payload["surfaceContractVersion"]?.jsonPrimitive?.int,
        )
    }

    @Test
    fun `the decision index says so when there is no corpus`() {
        val resources = ToolboxResources(snapshot(), null)

        val payload = resources.decisionsPayload()

        assertFalse(payload["available"]?.jsonPrimitive?.boolean ?: true)
        assertEquals(0, payload["decisions"]?.jsonArray?.size)
    }

    @Test
    fun `the decision index holds the decisions and neither the archive nor the backlog`() {
        val resources = ToolboxResources(snapshot(), storage(corpus()))

        val payload = resources.decisionsPayload()

        assertTrue(payload["available"]?.jsonPrimitive?.boolean == true)
        val slugs = payload["decisions"]?.jsonArray?.map { it.jsonObject["slug"]?.jsonPrimitive?.content }
        assertEquals(
            listOf("2026-10-02-second", "2026-10-01-first"),
            slugs,
            "the corpus rules decide what a decision is: the archive is not one, and " +
                "neither is the backlog",
        )
    }

    @Test
    fun `a decision is served by its slug`() {
        val resources = ToolboxResources(snapshot(), storage(corpus()))

        val body = resources.decisionBody("2026-10-01-first")

        assertTrue(body.startsWith("# The first decision"), body)
        assertTrue("date: 2026-10-01" in body, body)
        assertTrue("The body of the first decision." in body, body)
    }

    @Test
    fun `a slug that is not a slug is refused before anything is read`() {
        val resources = ToolboxResources(snapshot(), storage(corpus()))

        val body = resources.decisionBody("../../etc/passwd")

        assertTrue(body.startsWith("Not a decision slug"), body)
    }

    @Test
    fun `an archived decision is not reachable by name`() {
        val resources = ToolboxResources(snapshot(), storage(corpus()))

        val body = resources.decisionBody("2026-09-01-archived")

        assertTrue(body.startsWith("No decision named"), body)
    }

    @Test
    fun `a backlog entry is not reachable as a decision`() {
        val resources = ToolboxResources(snapshot(), storage(corpus()))

        val body = resources.decisionBody("2026-10-03-planned")

        assertTrue(body.startsWith("No decision named"), body)
    }

    @Test
    fun `a server without a corpus says so instead of pretending`() {
        val resources = ToolboxResources(snapshot(), null)

        val body = resources.decisionBody("2026-10-01-first")

        assertEquals("No corpus is available to this server.", body)
    }
}
