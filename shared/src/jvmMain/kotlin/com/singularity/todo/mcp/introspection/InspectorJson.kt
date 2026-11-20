package com.singularity.todo.mcp.introspection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val inspectorJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

internal fun JsonElement?.strictStringArg(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.stringArg(): String? = strictStringArg()

internal fun ok(payload: JsonObject): JsonObject =
    buildJsonObject {
        put("ok", true)
        put("result", payload)
    }

internal fun err(
    code: String,
    message: String,
    details: JsonObject? = null,
): JsonObject =
    buildJsonObject {
        put("ok", false)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                details?.let { put("details", it) }
            },
        )
    }
