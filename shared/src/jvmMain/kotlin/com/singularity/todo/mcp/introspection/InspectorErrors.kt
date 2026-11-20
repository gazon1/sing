package com.singularity.todo.mcp.introspection

import kotlinx.serialization.json.JsonObject

internal enum class InspectorErrorCode(val wire: String) {
    INVALID_ARGUMENTS("invalid_arguments"),
    UNKNOWN_SCREEN("unknown_screen"),
    UNKNOWN_ACTION("unknown_action"),
    INVALID_ACTION_INPUT("invalid_action_input"),
    ACTION_NOT_AVAILABLE("action_not_available"),
    SESSION_NOT_FOUND("session_not_found"),
    OPERATION_NOT_FOUND("operation_not_found"),
    OPERATION_TIMEOUT("operation_timeout"),
    OPERATION_FAILED("operation_failed"),
    UNSUPPORTED("unsupported"),
    INTERNAL("internal"),
}

internal class InspectorException(
    val code: InspectorErrorCode,
    override val message: String,
    val details: JsonObject? = null,
) : Exception(message)

internal fun InspectorException.toJson(): JsonObject = err(code.wire, message, details)
