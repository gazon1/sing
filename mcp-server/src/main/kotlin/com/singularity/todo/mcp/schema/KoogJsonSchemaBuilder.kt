package com.singularity.todo.mcp.schema

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts a Koog [ToolDescriptor] into an MCP [ToolSchema] (JSON Schema 2020-12).
 *
 * Required and optional [ToolParameterDescriptor]s become schema `properties`, with the
 * required ones gathered into the top-level `required` array.
 *
 * Recursive descent is required because `ToolParameterType.Object`, `List`, and `AnyOf`
 * can each contain nested parameter types. Descriptions are propagated through.
 */
object KoogJsonSchemaBuilder {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun build(descriptor: ToolDescriptor): ToolSchema {
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
            buildMap {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(properties))
                if (required.isNotEmpty()) {
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                }
            },
        )

        // NOTE: Do NOT embed "$schema" as a property inside schemaJson — the MCP client
        // will interpret it as a JSON Schema dialect URI reference and fail validation.
        // The schema string is carried solely by the ToolSchema.schema field.
        return ToolSchema(
            schema = json.encodeToString(JsonElement.serializer(), schemaJson),
            properties = schemaJson,
            required = required,
            defs = JsonObject(emptyMap()),
        )
    }

    private fun ToolParameterDescriptor.toJsonSchema(): JsonElement =
        type.toJsonSchema(description)

    private fun ToolParameterType.toJsonSchema(description: String): JsonElement = when (this) {
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

    private fun stringSchema(type: String, description: String): JsonObject =
        if (description.isNotEmpty()) {
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(type),
                    "description" to JsonPrimitive(description),
                ),
            )
        } else {
            JsonObject(mapOf("type" to JsonPrimitive(type)))
        }

    private fun enumSchema(entries: Array<String>, description: String): JsonObject =
        buildMap {
            put("type", JsonPrimitive("string"))
            put("enum", JsonArray(entries.map { JsonPrimitive(it) }))
            if (description.isNotEmpty()) put("description", JsonPrimitive(description))
        }.let { JsonObject(it) }

    private fun listSchema(itemsType: ToolParameterType, description: String): JsonObject =
        buildMap {
            put("type", JsonPrimitive("array"))
            put("items", itemsType.toJsonSchema(""))
            if (description.isNotEmpty()) put("description", JsonPrimitive(description))
        }.let { JsonObject(it) }

    private fun objectSchema(obj: ToolParameterType.Object, description: String): JsonObject =
        buildMap {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                JsonObject(
                    buildMap {
                        for (prop in obj.properties) {
                            put(prop.name, prop.toJsonSchema())
                        }
                    },
                ),
            )
            if (obj.requiredProperties.isNotEmpty()) {
                put(
                    "required",
                    JsonArray(obj.requiredProperties.map { JsonPrimitive(it) }),
                )
            }
            val additionalPropType = obj.additionalPropertiesType
            if (obj.additionalProperties == true && additionalPropType != null) {
                put("additionalProperties", additionalPropType.toJsonSchema(""))
            }
            if (description.isNotEmpty()) put("description", JsonPrimitive(description))
        }.let { JsonObject(it) }

    private fun anyOfSchema(types: Array<ToolParameterDescriptor>, description: String): JsonObject =
        buildMap {
            put("anyOf", JsonArray(types.map { it.toJsonSchema() }))
            if (description.isNotEmpty()) put("description", JsonPrimitive(description))
        }.let { JsonObject(it) }
}
