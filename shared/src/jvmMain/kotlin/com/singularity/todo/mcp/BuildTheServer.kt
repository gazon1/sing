package com.singularity.todo.mcp

import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.mcp.introspection.ScreenInspectorTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * The step that builds the server and registers every tool on it.
 *
 * The tool configuration is handed to the registrar here and not read again: the
 * toolbox a client sees is the toolbox the server announced when it started.
 */
internal object BuildTheServer : ToolServerStartupStep {
    override val title: String = "build the server"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val dependencies =
            checkNotNull(context.dependencies) {
                "The order does not resolve the dependencies before this step."
            }
        val configuration =
            checkNotNull(context.configuration) {
                "The order does not read the configuration before this step."
            }
        startupLog.i { "singularity-todo MCP server started" }
        context.server = build(dependencies, configuration, context.sessionContext, context.inspectorHostEnabled)
        return null
    }
}

/**
 * Builds the server and registers every tool on it.
 *
 * The tool configuration is handed to the registrar here and not read again: the
 * toolbox a client sees is the toolbox the server announced when it started.
 */
private fun build(
    dependencies: ServerDependencies,
    configuration: ToolConfiguration,
    sessionContext: SessionContextHolder,
    inspectorHostEnabled: Boolean,
): Server {
    val server =
        Server(
            serverInfo =
                Implementation(
                    name = "singularity-todo",
                    // The real application version, from the canonical source the desktop app
                    // and the Android build both use. A literal here would tell a client it had
                    // connected to a version nobody could check against the checkout.
                    version = appVersion().name,
                    title = null,
                    websiteUrl = null,
                    icons = emptyList(),
                ),
            options =
                ServerOptions(
                    capabilities =
                        ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = true),
                            resources = ServerCapabilities.Resources(listChanged = true),
                        ),
                ),
            instructions = instructions(inspectorHostEnabled),
        )
    val registrar =
        ToolRegistrar(
            server = server,
            tools = dependencies.tools,
            toolFlags = configuration.flags,
            configurationStatus = configuration.status,
            configurationFetchedAtEpochMillis = configuration.fetchedAtEpochMillis,
            recorder = dependencies.recorder,
            sessionContext = { sessionContext.current() },
        )
    registrar.registerAll()
    registerScreenInspectorHost(server, inspectorHostEnabled)
    ToolboxTool(registrar.snapshot).register(server)
    ToolboxResources(
        snapshot = registrar.snapshot,
        storage = dependencies.adrStorage,
    ).register(server)
    RegistrySnapshot.write(registrar.snapshot, dependencies.dataDirectory)
    startupLog.i {
        "Toolbox: ${registrar.snapshot.registered.size} tool(s) registered, " +
            "${registrar.snapshot.disabled.size} disabled, " +
            "fingerprint ${registrar.snapshot.identity.activeToolsetFingerprint}"
    }
    if (registrar.snapshot.unmatchedFlags.isNotEmpty()) {
        // A flag that names no tool switched nothing off. The operator who set it
        // believes it did, so the server says so rather than staying quiet.
        startupLog.w {
            "Remote configuration names ${registrar.snapshot.unmatchedFlags.size} tool(s) " +
                "this server does not expose: ${registrar.snapshot.unmatchedFlags.joinToString()}"
        }
    }
    return server
}

/**
 * The server's own description of what it exposes.
 *
 * The instructions name the screen inspector only when the host is enabled. A server that
 * advertises a toolbox it did not register sends an agent looking for tools that are not there;
 * `describe_toolbox` says what is actually registered, and these instructions agree with it.
 */
private fun instructions(inspectorHostEnabled: Boolean): String {
    val screenInspector =
        if (inspectorHostEnabled) {
            " Screen inspector tools (screen_list/open/describe/snapshot/actions/invoke/wait/cancel/close) " +
                "drive supported screens headlessly via the canonical introspection contract."
        } else {
            " Screen inspection is not enabled in this process; the screen_* tools are not registered."
        }
    return "Singularity Todo MCP server. Use tools to read/write tasks, notes, projects, tags, and " +
        "ADR entries." + screenInspector +
        " Call describe_toolbox to see which tools this server is exposing and why. " +
        "Surface contract version: ${McpToolCatalog.SURFACE_CONTRACT_VERSION}."
}

/**
 * Registers the screen-inspector tools only when the host may run.
 *
 * The decision is made once, at the entry point, and travels here as [inspectorHostEnabled]; this
 * function only applies it. Without the opt-in the toolbox has no `screen_*` tool at all, so a
 * request cannot reach a dispatch surface that was never registered.
 */
internal fun registerScreenInspectorHost(
    server: Server,
    inspectorHostEnabled: Boolean,
) {
    if (!inspectorHostEnabled) {
        startupLog.i {
            "Screen inspector host not opted in; no screen inspector host starts. Pass " +
                "${McpServerCommandProvider.INSPECTOR_HOST_FLAG} to opt in."
        }
        return
    }
    ScreenInspectorTools().register(server)
}
