package com.singularity.todo.mcp.introspection

import kotlinx.serialization.json.JsonObject

internal data class ScreenCapabilityInfo(val id: String, val description: String, val params: JsonObject)

internal interface ScreenSession : AutoCloseable {
    val screenId: String
    val sessionId: String
    val params: JsonObject

    fun describe(): JsonObject

    fun snapshot(): JsonObject

    fun actions(): JsonObject

    suspend fun invoke(
        action: String,
        input: JsonObject,
    ): JsonObject

    suspend fun wait(
        operationId: String,
        timeoutMs: Long,
    ): JsonObject

    fun cancel(operationId: String): JsonObject
}
