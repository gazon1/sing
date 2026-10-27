package com.singularity.todo.mcp

import com.singularity.todo.feature.ai.McpToolCatalog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `describe_toolbox` — what is this toolbox, and what is it connected to?
 *
 * ## Why a transport-level tool
 *
 * The catalogue, the Koin list and the remote-config flags are three separate
 * declarations, and only the *registrar* knows which of them won. A catalogue entry is
 * the intent; a Koin binding is the wiring; the flags are the operator's decision. A
 * tool that reports the intent would report a toolbox that may not be the one the client
 * is holding. So this tool is not a Koog tool, is not in the catalogue, and is not in
 * Koin: it is registered directly on the server, next to the registrar that owns the
 * answer.
 *
 * ## What it does not report
 *
 * No absolute paths, no remote-config contents, no secrets, no process state. The
 * disabled-tool list is bounded, because a config that disables two hundred tools is a
 * config problem, not something an agent should page through.
 *
 * ## How the payload may change
 *
 * This payload is a contract with every client that reads it, and clients are not
 * upgraded with the server. So:
 *
 * - a **new** field is allowed — a client that does not know it ignores it;
 * - a **renamed** field is not — it breaks every client that reads the old name;
 * - a **removed** field is not — it breaks every client that reads it.
 *
 * The shape is pinned by `ToolboxToolTest`, so a rename fails the build rather than
 * the clients. The same rule holds for the resource URIs, which are pinned by
 * `ToolboxResourcesTest`.
 */
class ToolboxTool(private val snapshot: ToolsetSnapshot) {

    private val json = Json { prettyPrint = false }

    fun register(server: Server) {
        server.addTool(
            NAME,
            DESCRIPTION,
            inputSchema(),
            "",
            null,
            null,
            null,
            kotlinx.serialization.json.JsonObject(emptyMap()),
        ) { request: CallToolRequest -> describe(request) }
    }

    private fun inputSchema(): ToolSchema = ToolSchema(
        schema = null as String?,
        properties = JsonObject(
            mapOf(
                "domain" to buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Only list the tools of this domain (tasks, projects, notes, tags, adr, " +
                            "docs, ai). Omit for every domain.",
                    )
                },
                "includeDisabled" to buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also list the tools the remote config has switched off.")
                },
            ),
        ),
        required = emptyList(),
        defs = null as JsonObject?,
    )

    private fun describe(request: CallToolRequest): CallToolResult {
        // Arguments arrive as JsonElement: `as? String` / `as? Boolean` never matched, and
        // `jsonPrimitive` throws on an object and reads JsonNull as the string "null".
        val domain = request.arguments?.get("domain").argumentString()?.takeIf { it.isNotBlank() }
        val includeDisabled =
            request.arguments?.get("includeDisabled").argumentString()?.toBooleanStrictOrNull() ?: false

        val registered = snapshot.registered.filter { domain == null || it.domain == domain }
        val disabled = snapshot.disabled.filter { domain == null || it.domain == domain }

        val payload = payload(registered, disabled, includeDisabled)
        val text = json.encodeToString(JsonObject.serializer(), payload)
        return CallToolResult(
            content = listOf(TextContent(text = text)),
            structuredContent = payload,
            isError = false,
        )
    }

    /**
     * The payload, as the client receives it.
     *
     * Internal rather than private: the *shape* of this object is a contract with every
     * client that reads it, and a contract that no test pins is a contract that can be
     * broken by a rename. `ToolboxToolTest` asserts the keys.
     */
    internal fun payload(
        registered: List<ToolboxToolEntry>,
        disabled: List<ToolboxToolEntry>,
        includeDisabled: Boolean,
    ): JsonObject = buildJsonObject {
        put(
            "toolset",
            buildJsonObject {
                put("surfaceContractVersion", McpToolCatalog.SURFACE_CONTRACT_VERSION)
                put("buildVersion", snapshot.identity.buildVersion)
                put("catalogFingerprint", snapshot.identity.catalogFingerprint)
                put("activeToolsetFingerprint", snapshot.identity.activeToolsetFingerprint)
                put("configurationStatus", snapshot.configurationStatus.name.lowercase())
                put("registeredCount", registered.size)
                put("domains", domains(registered))
            },
        )
        snapshot.identity.checkout?.let { checkout ->
            put(
                "checkout",
                buildJsonObject {
                    put("sha", checkout.sha)
                    put("dirty", checkout.dirty)
                },
            )
        }
        if (includeDisabled && disabled.isNotEmpty()) {
            put(
                "disabled",
                buildJsonArray {
                    disabled.take(MAX_DISABLED_LISTED).sortedBy { it.name }.forEach { tool ->
                        add(buildJsonObject { put("name", tool.name) })
                    }
                },
            )
            if (disabled.size > MAX_DISABLED_LISTED) {
                put("disabledTruncated", true)
                put("disabledCount", disabled.size)
            }
        }
        if (snapshot.unmatchedFlags.isNotEmpty()) {
            // A flag that named no tool switched nothing off. Reporting it is the
            // difference between "the operator's flag did nothing" and "the operator's
            // flag is still in force, and the tool it names is missing".
            put(
                "unmatchedFlags",
                buildJsonArray {
                    snapshot.unmatchedFlags.take(MAX_UNMATCHED_LISTED).forEach { flag ->
                        add(buildJsonObject { put("name", flag) })
                    }
                },
            )
            if (snapshot.unmatchedFlags.size > MAX_UNMATCHED_LISTED) {
                put("unmatchedFlagsTruncated", true)
                put("unmatchedFlagsCount", snapshot.unmatchedFlags.size)
            }
        }
    }

    private fun domains(registered: List<ToolboxToolEntry>): JsonObject = buildJsonObject {
        registered.groupBy { it.domain }
            .toSortedMap()
            .forEach { (toolDomain, tools) ->
                put(
                    toolDomain,
                    buildJsonObject {
                        put(
                            "tools",
                            buildJsonArray {
                                tools.sortedBy { it.name }.forEach { tool ->
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("readOnly", tool.readOnly)
                                            put("destructive", tool.destructive)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
    }

    companion object {
        const val NAME = "describe_toolbox"
        const val DESCRIPTION =
            "Describe the toolbox this server is exposing: which tools exist, which are " +
                "switched off, which build and checkout it is, and which configuration it " +
                "applied. Call this when a tool is missing or behaves unexpectedly."

        /** The disabled list is a diagnostic, not a page of the configuration. */
        const val MAX_DISABLED_LISTED = 20

        /** The unmatched-flag list is a diagnostic too, and the same size. */
        const val MAX_UNMATCHED_LISTED = 20
    }
}

/**
 * A call argument's text, or null when it is absent, JSON `null`, or not a primitive.
 *
 * `as? String` never matches a `JsonElement`, `jsonPrimitive` throws on an object or an
 * array, and a `JsonNull` reads as the string "null" — so `{"domain": null}`, which is what
 * a generated client sends for an omitted optional, would have filtered every domain out.
 */
internal fun JsonElement?.argumentString(): String? = (this as? JsonPrimitive)?.contentOrNull
