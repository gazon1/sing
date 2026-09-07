package com.singularity.todo.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull as XJsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.core.context.GlobalContext

/**
 * Bridges Koog [SimpleTool] instances to the MCP [Server] tool registry.
 *
 * Each Koog tool exposes:
 * - [Tool.descriptor]: name, description, required/optional [ToolParameterDescriptor] list
 * - [Tool.argsType]: TypeToken for the input type
 * - A result String already JSON-encoded by the tool
 *
 * We convert Koog parameter descriptors → JSON Schema 2020-12 for the MCP input schema.
 * JSON conversion between kotlinx-serialization and Koog's JSONElement is handled explicitly.
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
        tools.forEach { tool -> registerOne(tool) }
    }

    private fun registerOne(tool: Tool<*, *>) {
        val descriptor = tool.descriptor
        val name = descriptor.name
        val description = descriptor.description
        val toolSchema = buildToolSchema(descriptor)
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
        ) { request: CallToolRequest ->
            handleToolCall(tool, request)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleToolCall(tool: Tool<*, *>, request: CallToolRequest): CallToolResult {
        val koogArgs: JSONObject = request.arguments?.let { args ->
            JSONObject(args.mapValues { (_, v) -> v.toKoogJSONElement() })
        } ?: JSONObject(emptyMap())

        return try {
            val koogTool = tool as SimpleTool<Any>
            val args: Any = koogTool.decodeArgs(koogArgs, serializer)

            val resultString: String = runBlocking {
                koogTool.execute(args)
            }

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

    // ─── ToolSchema builder from Koog ToolParameterType ────────────────────────

    private fun buildToolSchema(descriptor: ToolDescriptor): ToolSchema {
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<String>()

        descriptor.requiredParameters.forEach { param ->
            properties[param.name] = param.toJsonSchema()
            required.add(param.name)
        }

        descriptor.optionalParameters.forEach { param ->
            properties[param.name] = param.toJsonSchema()
        }

        val schemaJson = JsonObject(
            mapOf<String, JsonElement>().let { m ->
                val result = m.toMutableMap()
                result["type"] = JsonPrimitive("object")
                result["properties"] = JsonObject(properties)
                if (required.isNotEmpty()) {
                    result["required"] = JsonArray(required.map { JsonPrimitive(it) })
                }
                result
            }
        )

        return ToolSchema(
            schema = json.encodeToString(JsonElement.serializer(), schemaJson),
            properties = schemaJson,
            required = required,
            defs = JsonObject(emptyMap()),
        )
    }

    private fun ToolParameterDescriptor.toJsonSchema(): JsonElement {
        return type.toJsonSchema(description)
    }

    private fun ToolParameterType.toJsonSchema(description: String): JsonElement {
        return when (this) {
            is ToolParameterType.String -> stringSchema("string", description)
            is ToolParameterType.Integer -> stringSchema("integer", description)
            is ToolParameterType.Float -> stringSchema("number", description)
            is ToolParameterType.Boolean -> stringSchema("boolean", description)
            is ToolParameterType.Null -> stringSchema("null", description)
            is ToolParameterType.Enum -> enumSchema(this.entries, description)
            is ToolParameterType.List -> listSchema(this.itemsType, description)
            is ToolParameterType.Object -> objectSchema(this, description)
            is ToolParameterType.AnyOf -> anyOfSchema(this.types, description)
        }
    }

    private fun stringSchema(type: String, description: String): JsonObject {
        return if (description.isNotEmpty()) {
            JsonObject(mapOf(
                "type" to JsonPrimitive(type),
                "description" to JsonPrimitive(description)
            ))
        } else {
            JsonObject(mapOf("type" to JsonPrimitive(type)))
        }
    }

    private fun enumSchema(entries: Array<String>, description: String): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["type"] = JsonPrimitive("string")
        map["enum"] = JsonArray(entries.map { JsonPrimitive(it) })
        if (description.isNotEmpty()) {
            map["description"] = JsonPrimitive(description)
        }
        return JsonObject(map)
    }

    private fun listSchema(itemsType: ToolParameterType, description: String): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["type"] = JsonPrimitive("array")
        map["items"] = itemsType.toJsonSchema("")
        if (description.isNotEmpty()) {
            map["description"] = JsonPrimitive(description)
        }
        return JsonObject(map)
    }

    private fun objectSchema(obj: ToolParameterType.Object, description: String): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["type"] = JsonPrimitive("object")

        val props = mutableMapOf<String, JsonElement>()
        for (prop in obj.properties) {
            props[prop.name] = prop.toJsonSchema()
        }
        map["properties"] = JsonObject(props)

        if (obj.requiredProperties.isNotEmpty()) {
            map["required"] = JsonArray(obj.requiredProperties.map { JsonPrimitive(it) })
        }
        val additionalPropType = obj.additionalPropertiesType
        if (obj.additionalProperties == true && additionalPropType != null) {
            map["additionalProperties"] = additionalPropType.toJsonSchema("")
        }
        if (description.isNotEmpty()) {
            map["description"] = JsonPrimitive(description)
        }
        return JsonObject(map)
    }

    private fun anyOfSchema(types: Array<ToolParameterDescriptor>, description: String): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["anyOf"] = JsonArray(types.map { it.toJsonSchema() })
        if (description.isNotEmpty()) {
            map["description"] = JsonPrimitive(description)
        }
        return JsonObject(map)
    }

    // ─── kotlinx.serialization.json ↔ Koog JSONElement ─────────────────────────

    private fun JsonElement.toKoogJSONElement(): JSONElement {
        return when (this) {
            is JsonPrimitive -> {
                when {
                    isString -> ai.koog.serialization.JSONLiteral(content, isString = true)
                    content == "true" -> ai.koog.serialization.JSONLiteral("true", isString = false)
                    content == "false" -> ai.koog.serialization.JSONLiteral("false", isString = false)
                    content == "null" -> JSONNull
                    content.toDoubleOrNull() != null -> ai.koog.serialization.JSONLiteral(content, isString = false)
                    else -> ai.koog.serialization.JSONLiteral(content, isString = true)
                }
            }
            is JsonObject -> JSONObject(this.mapValues { (_, v) -> v.toKoogJSONElement() })
            is JsonArray -> JSONArray(this.map { it.toKoogJSONElement() })
            XJsonNull -> JSONNull
        }
    }
}
