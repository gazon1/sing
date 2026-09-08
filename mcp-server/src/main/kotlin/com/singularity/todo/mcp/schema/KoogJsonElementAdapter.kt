package com.singularity.todo.mcp.schema

import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adapter between kotlinx-serialization [JsonElement] (used by MCP) and
 * Koog [JSONElement] (used by `SimpleTool<T>.decodeArgs`).
 *
 * The MCP SDK deserializes tool-call arguments into kotlinx-serialization trees; Koog's
 * tool expects its own tree. This is the only correct bridge — nulls, booleans, and
 * numbers must round-trip without coercion.
 */
fun JsonElement.toKoog(): JSONElement = when (this) {
    is JsonPrimitive -> {
        when {
            isString -> JSONLiteral(content, isString = true)
            content == "true" -> JSONLiteral("true", isString = false)
            content == "false" -> JSONLiteral("false", isString = false)
            content == "null" -> JSONNull
            content.toDoubleOrNull() != null -> JSONLiteral(content, isString = false)
            else -> JSONLiteral(content, isString = true)
        }
    }

    is JsonObject -> JSONObject(this.mapValues { (_, v) -> v.toKoog() })

    is JsonArray -> JSONArray(this.map { it.toKoog() })

    JsonNull -> JSONNull
}
