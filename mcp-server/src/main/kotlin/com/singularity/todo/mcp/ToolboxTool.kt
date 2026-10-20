package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
        val domain = (request.arguments?.get("domain") as? String)?.takeIf { it.isNotBlank() }
        val includeDisabled = request.arguments?.get("includeDisabled") as? Boolean ?: false

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

    private fun payload(
        registered: List<ToolboxToolEntry>,
        disabled: List<ToolboxToolEntry>,
        includeDisabled: Boolean,
    ): JsonObject = buildJsonObject {
        put(
            "toolset",
            buildJsonObject {
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
    }
}
