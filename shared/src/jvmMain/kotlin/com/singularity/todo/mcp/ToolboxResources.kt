package com.singularity.todo.mcp

import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.feature.ai.tools.AdrStorage
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The read-only half of the surface, served over the protocol.
 *
 * ## Why resources at all
 *
 * A tool is a *request*: the client decides to call it, and the arguments go in. A
 * resource is a *document*: the client asks for `singularity://decisions` and gets the
 * index. The two are different gestures, and the corpus is made of documents — reading a
 * decision is not an operation, it is a lookup by name.
 *
 * The tools remain the primary path: resource support in MCP clients is uneven, and an
 * agent that only speaks tools must still be able to find the decisions. The resources
 * are the convenient door, not the only one.
 *
 * ## The access rule
 *
 * A read-only resource is not automatically an *authorised* one. A slug is resolved
 * through the corpus discovery rules, never by concatenating it into a path: an
 * arbitrary slug must not be able to read a file outside the corpus, and an archived or
 * ignored file must not be reachable by guessing its name.
 */
class ToolboxResources(private val snapshot: ToolsetSnapshot, private val storage: AdrStorage?) {

    private val json = Json { prettyPrint = false }

    fun register(server: Server) {
        server.addResource(
            uri = TOOLBOX_URI,
            name = "Toolbox",
            description = "The tools this server exposes, grouped by domain.",
            mimeType = "application/json",
        ) { request ->
            ReadResourceResult(
                listOf(
                    TextResourceContents(
                        text = json.encodeToString(JsonObject.serializer(), toolboxPayload()),
                        uri = request.params.uri,
                        mimeType = "application/json",
                    ),
                ),
            )
        }

        server.addResource(
            uri = DECISIONS_URI,
            name = "Decision index",
            description = "Every decision in the corpus: slug, title, date, status and tags.",
            mimeType = "application/json",
        ) { request ->
            ReadResourceResult(
                listOf(
                    TextResourceContents(
                        text = json.encodeToString(JsonObject.serializer(), decisionsPayload()),
                        uri = request.params.uri,
                        mimeType = "application/json",
                    ),
                ),
            )
        }

        server.addResourceTemplate(
            uriTemplate = "$DECISION_URI/{slug}",
            name = "Decision",
            description = "One decision, by slug, with its body.",
            mimeType = "text/markdown",
        ) { request, variables ->
            val slug = variables["slug"].orEmpty()
            val body = decisionBody(slug)
            ReadResourceResult(
                listOf(
                    TextResourceContents(
                        text = body,
                        uri = request.params.uri,
                        mimeType = "text/markdown",
                    ),
                ),
            )
        }
    }

    /**
     * The documents, as the client receives them.
     *
     * Internal rather than private, for the same reason as `ToolboxTool.payload`: the
     * shape is a contract, and a contract no test pins is a contract a rename breaks.
     */
    internal fun toolboxPayload(): JsonObject = buildJsonObject {
        // The contract version, so a client reading the resource can tell whether the
        // shape it knows is the shape this server speaks — the same reason
        // `describe_toolbox` carries it.
        put("surfaceContractVersion", McpToolCatalog.SURFACE_CONTRACT_VERSION)
        put("buildVersion", snapshot.identity.buildVersion)
        put("catalogFingerprint", snapshot.identity.catalogFingerprint)
        put("activeToolsetFingerprint", snapshot.identity.activeToolsetFingerprint)
        put("configurationStatus", snapshot.configurationStatus.name.lowercase())
        put("tools", toolboxTools())
    }

    private fun toolboxTools(): JsonArray = buildJsonArray {
        snapshot.registered.sortedBy { it.name }.forEach { tool ->
            add(
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("domain", tool.domain)
                },
            )
        }
    }

    internal fun decisionsPayload(): JsonObject = buildJsonObject {
        val storage = this@ToolboxResources.storage
        if (storage == null) {
            put("available", false)
            put("decisions", buildJsonArray { })
            return@buildJsonObject
        }
        put("available", true)
        put("decisions", decisionIndex(storage))
    }

    private fun tagEntry(tag: String): JsonObject = buildJsonObject { put("name", tag) }

    private fun decisionIndex(storage: AdrStorage): JsonArray = buildJsonArray {
        storage.listAdrs().forEach { adr ->
            add(
                buildJsonObject {
                    put("slug", adr.slug)
                    put("title", adr.title)
                    put("date", adr.date)
                    put("status", adr.status)
                    put("tags", buildJsonArray { adr.tags.forEach { tag -> add(tagEntry(tag)) } })
                },
            )
        }
    }

    /**
     * The body of one decision, or a defined not-found.
     *
     * The slug goes through [AdrStorage.readAdr], which resolves it against the corpus
     * and refuses anything that is not a valid slug. That is the same path the
     * `read_adr` tool takes, so a resource and a tool cannot disagree about which
     * decisions exist.
     */
    internal fun decisionBody(slug: String): String {
        val storage = this.storage
        return when {
            storage == null -> "No corpus is available to this server."

            !storage.isValidSlug(slug) -> "Not a decision slug: $slug"

            else -> storage.readAdr(slug)?.let { adr ->
                "# ${adr.title}\n\ndate: ${adr.date}\nstatus: ${adr.status}\n" +
                    "tags: ${adr.tags}\n\n${adr.body}"
            } ?: "No decision named '$slug'."
        }
    }

    companion object {
        const val TOOLBOX_URI = "singularity://toolbox"
        const val DECISIONS_URI = "singularity://decisions"
        const val DECISION_URI = "singularity://decision"
    }
}
