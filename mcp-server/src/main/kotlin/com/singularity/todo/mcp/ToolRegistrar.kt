package com.singularity.todo.mcp
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.singularity.todo.core.ai.filterTools
import com.singularity.todo.core.observability.ToolCallContext
import com.singularity.todo.core.observability.ToolCallError
import com.singularity.todo.core.observability.ToolCallRecorder
import com.singularity.todo.core.observability.ToolCallSource
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.mcp.errors.ErrorMapper
import com.singularity.todo.mcp.errors.McpToolError
import com.singularity.todo.mcp.schema.KoogJsonSchemaBuilder
import com.singularity.todo.mcp.schema.toKoog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject


/**
 * Bridges Koog [SimpleTool] instances to the MCP [Server] tool registry.
 *
 * Each Koog tool exposes:
 * - [Tool.descriptor]: name, description, required/optional parameter list (used by
 *   [KoogJsonSchemaBuilder] for the JSON-Schema input).
 * - `decodeArgs(args, serializer)`: parse a Koog [JSONObject] into the tool's typed input.
 * - An `execute(args)` that returns a JSON-encoded `String`.
 *
 * Per-call pipeline: decode kotlinx [JsonElement] → Koog [JSONElement] via
 * [toKoog], decode into typed args, execute under `runBlocking`, then forward the result
 * as both `text` content and (when parseable) `structuredContent`.
 *
 * ## The kill switch
 *
 * A tool whose remote-config flag is `false` is **never registered**: it does not appear
 * in `tools/list` and it cannot be called. That is the point — a disabled tool that is
 * still in the schema costs the agent context and invites a call that must fail.
 *
 * The in-app agent reads these flags too, and the switch is applied by the same rule in
 * both places — [com.singularity.todo.core.ai.filterTools]. Until 2026-10-20 neither
 * applied it: the tool ID the rule keys on could not be resolved, so a flag never
 * matched a tool. The rule was fixed at its owner; see ADR
 * `2026-10-20-the-kill-switch-was-never-applied`.
 *
 * The flags are **global**: the remote-config cache is a single row, so a disabled tool
 * is disabled for every profile and every consumer of that database. They are an
 * *operational* control — a way to take a broken tool out of the loop — and not a
 * safety boundary: when the configuration cannot be read, the policy is fail-open and
 * every tool is enabled. [snapshot] reports which of those two happened.
 */
class ToolRegistrar(
    private val server: Server,
    private val tools: List<Tool<*, *>>,
    private val toolFlags: Map<String, Boolean> = emptyMap(),
    private val configurationStatus: ConfigurationStatus = ConfigurationStatus.UNAVAILABLE,
    private val configurationFetchedAtEpochMillis: Long? = null,
    private val recorder: ToolCallRecorder? = null,
    private val sessionContext: () -> ToolCallContext = { ToolCallContext() },
) {

    /**
     * The tool list is handed in, not fetched from a global context.
     *
     * The registrar used to reach into the global Koin context itself, which made the
     * only test of the kill switch depend on global state — and a test that starts and
     * stops a global container races with every other test in the same JVM. Resolving
     * the list at the edge (the server entry point) keeps this class a pure function of
     * its arguments, which is what the test wants to assert about.
     */
    private val enabledTools: List<Tool<*, *>> = filterTools(tools, toolFlags)
    private val disabledTools: List<Tool<*, *>> = tools.filterNot { it in enabledTools }

    /**
     * What the toolbox looks like right now, and why.
     *
     * Built once, here, because this is the only place that knows both halves: the
     * catalogue says which tools are *declared*, and the filtering says which are
     * *exposed*. Everything that reports on the toolbox reads this one object.
     */
    val snapshot: ToolsetSnapshot = buildSnapshot()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val serializer = KotlinxSerializer(json)

    fun registerAll() {
        enabledTools.forEach { registerOne(it) }
    }

    private fun buildSnapshot(): ToolsetSnapshot {
        val registered = enabledTools.map { describe(it) }
        val disabled = disabledTools.map { describe(it) }
        // A flag that names no exposed tool did not switch anything off — and the
        // operator who set it believes it did. The names are reported, not dropped.
        val exposed = (registered + disabled).map { it.name }.toSet()
        val unmatched = toolFlags.keys.filterNot { it in exposed }.sorted()
        return ToolsetSnapshot(
            identity = ToolboxIdentity(
                buildVersion = ToolboxIdentityFactory.buildVersion(),
                checkout = ToolboxIdentityFactory.checkout(),
                catalogFingerprint = ToolboxIdentityFactory.catalogFingerprint(),
                activeToolsetFingerprint = ToolboxIdentityFactory.activeToolsetFingerprint(registered),
            ),
            registered = registered,
            disabled = disabled,
            configurationStatus = configurationStatus,
            configurationFetchedAtEpochMillis = configurationFetchedAtEpochMillis,
            unmatchedFlags = unmatched,
        )
    }

    private fun describe(tool: Tool<*, *>): ToolboxToolEntry {
        val descriptor = tool.descriptor
        val entry = McpToolCatalog.byName(descriptor.name)
        val annotations = entry?.annotations
        return ToolboxToolEntry(
            name = descriptor.name,
            description = descriptor.description,
            domain = entry?.domain ?: "unknown",
            readOnly = annotations?.readOnlyHint ?: false,
            destructive = annotations?.destructiveHint ?: false,
            idempotent = annotations?.idempotentHint ?: false,
            requiredParameters = descriptor.requiredParameters.map { it.name },
            optionalParameters = descriptor.optionalParameters.map { it.name },
        )
    }

    private fun registerOne(tool: Tool<*, *>) {
        val descriptor = tool.descriptor
        val name = descriptor.name
        val description = descriptor.description
        val inputSchema: ToolSchema = KoogJsonSchemaBuilder.build(descriptor)
        val annotations = McpToolCatalog.byName(name)?.annotations

        // Output schema: we pass an empty schema so the MCP client does not
        // validate structuredContent against the input schema. Koog does not
        // expose a separate output schema descriptor, and passing inputSchema
        // here (previous bug) caused Fred Perry's client to reject responses
        // whose JSON lacked required input fields (e.g. write_adr returning
        // {slug,path,title} but the client expected {slug,title,body}).
        val emptyOutputSchema = ToolSchema(
            schema = null as String?,
            properties = JsonObject(emptyMap()),
            required = emptyList(),
            defs = null as JsonObject?,
        )

        server.addTool(
            name,
            description,
            inputSchema,
            "",
            emptyOutputSchema,
            annotations?.toMcpAnnotations(),
            ToolExecution(TaskSupport.Optional),
            JsonObject(emptyMap()),
        ) { request: CallToolRequest -> handleToolCall(tool, request) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleToolCall(tool: Tool<*, *>, request: CallToolRequest): CallToolResult {
        // The measurement happens here, at the dispatch boundary, because this is the
        // only place that knows the call's arguments, its outcome and its error kind.
        // A decorator would have to wrap every tool to reach the same place, and it
        // would break the `SimpleTool` cast the decoder below relies on.
        val recorder = recorder
        if (recorder == null) return dispatch(tool, request)
        return recorder.timed(
            tool = tool.descriptor.name,
            source = ToolCallSource.MCP,
            context = sessionContext(),
            classify = ::classifyFailure,
        ) { dispatch(tool, request) }
    }

    /**
     * The error *code* of a failure, and whether the caller can act on it.
     *
     * A code, never the message: an exception message can carry arguments, paths and
     * server responses, and a record outlives the session it describes.
     */
    private fun classifyFailure(error: Throwable): ToolCallError = when (error) {
        is McpToolError.Validation -> ToolCallError("validation", business = true)
        is McpToolError.NotFound -> ToolCallError("not_found", business = true)
        is McpToolError.Conflict -> ToolCallError("conflict", business = true)
        is McpToolError.Unauthorized -> ToolCallError("unauthorized", business = true)
        is McpToolError.Internal -> ToolCallError("internal", business = false)
        else -> ToolCallError("internal", business = false)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun dispatch(tool: Tool<*, *>, request: CallToolRequest): CallToolResult {
        val koogArgs: JSONObject = request.arguments?.let { args ->
            JSONObject(args.mapValues { (_, v) -> v.toKoog() })
        } ?: JSONObject(emptyMap())

        return try {
            val koogTool = tool as SimpleTool<Any>
            val args: Any = decodeArguments(koogTool, koogArgs)

            val resultString: String = koogTool.execute(args)

            val structuredContent = runCatching {
                json.parseToJsonElement(resultString).jsonObject
            }.getOrNull()

            CallToolResult(
                content = listOf(TextContent(text = resultString)),
                structuredContent = structuredContent,
                isError = false,
            )
        } catch (e: McpToolError) {
            // Two-tier error model: business errors surface as isError=true text
            // (LLM can read & self-correct); Internal errors become JSON-RPC -32603
            // (server-side log only — never leak stack traces to the LLM).
            ErrorMapper.toResult(e)
        } catch (e: Exception) {
            ErrorMapper.toResult(McpToolError.Internal(e.message ?: "unknown", e))
        }
    }

    /**
     * Decodes the caller's arguments into the tool's input.
     *
     * ## Why this is not an internal error
     *
     * Arguments that do not fit the tool — a missing field, a value outside the set the
     * tool accepts — are the caller's mistake, and the caller can act on being told
     * so: the text goes back with `isError: true` and the model reads it and tries
     * again with something that fits.
     *
     * Letting it fall through to the internal tier made it a JSON-RPC error instead: a
     * protocol-level failure the caller cannot correct, over a typo in an argument.
     */
    private fun decodeArguments(tool: SimpleTool<Any>, koogArgs: JSONObject): Any = try {
        tool.decodeArgs(koogArgs, serializer)
    } catch (malformed: SerializationException) {
        throw McpToolError.Validation(
            field = tool.descriptor.name,
            text = "The arguments do not fit the tool \"${tool.descriptor.name}\": ${malformed.message}",
            cause = malformed,
        )
    } catch (malformed: IllegalArgumentException) {
        throw McpToolError.Validation(
            field = tool.descriptor.name,
            text = "The arguments do not fit the tool \"${tool.descriptor.name}\": ${malformed.message}",
            cause = malformed,
        )
    }
}
