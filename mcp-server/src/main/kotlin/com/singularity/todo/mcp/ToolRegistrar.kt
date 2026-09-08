package com.singularity.todo.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koin.core.context.GlobalContext

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
 */
class ToolRegistrar(private val server: Server) {

    private val koin = GlobalContext.get()
    @Suppress("UNCHECKED_CAST")
    private val tools: List<Tool<*, *>> = koin.get()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val serializer = KotlinxSerializer(json)

    fun registerAll() {
        tools.forEach { registerOne(it) }
    }

    private fun registerOne(tool: Tool<*, *>) {
        val descriptor = tool.descriptor
        val name = descriptor.name
        val description = descriptor.description
        val toolSchema: ToolSchema = KoogJsonSchemaBuilder.build(descriptor)
        val annotations = TOOL_ANNOTATIONS[name]

        server.addTool(
            name,
            description,
            toolSchema,
            "",
            toolSchema,
            annotations?.toMcpAnnotations(),
            ToolExecution(TaskSupport.Optional),
            JsonObject(emptyMap()),
        ) { request: CallToolRequest -> handleToolCall(tool, request) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleToolCall(tool: Tool<*, *>, request: CallToolRequest): CallToolResult {
        val koogArgs: JSONObject = request.arguments?.let { args ->
            JSONObject(args.mapValues { (_, v) -> v.toKoog() })
        } ?: JSONObject(emptyMap())

        return try {
            val koogTool = tool as SimpleTool<Any>
            val args: Any = koogTool.decodeArgs(koogArgs, serializer)

            val resultString: String = runBlocking { koogTool.execute(args) }

            val structuredContent = runCatching {
                json.parseToJsonElement(resultString).jsonObject
            }.getOrNull()

            CallToolResult(
                content = listOf(TextContent(text = resultString)),
                structuredContent = structuredContent,
                isError = false,
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent(text = "[InternalError] ${e.message ?: "unknown"}")),
                isError = true,
            )
        }
    }
}
