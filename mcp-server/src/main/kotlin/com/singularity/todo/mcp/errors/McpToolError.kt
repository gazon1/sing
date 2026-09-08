package com.singularity.todo.mcp.errors

/**
 * Two-tier error model for MCP tools.
 *
 * Business errors (Validation / NotFound / Conflict / Unauthorized) → isError: true
 * Internal errors (programming bugs, infrastructure failures) → JSON-RPC error -32603
 *
 * Never expose stack traces, internal paths, or class names in business errors.
 * The LLM reads the text and self-corrects; internals are logged server-side.
 *
 * Extends Throwable so that tools throwing [McpToolError] from suspend execute()
 * propagate cleanly through `runBlocking { tool.execute(args) }` and can be
 * caught by the tool registrar's try/catch.
 */
sealed class McpToolError : Throwable() {
    /** Missing or invalid input field. */
    data class Validation(val field: String, val text: String) : McpToolError()

    /** Requested resource does not exist in the user's data. */
    data class NotFound(val resource: String, val id: String) : McpToolError()

    /** Conflict with current server state (duplicate ID, version mismatch, etc.). */
    data class Conflict(val reason: String) : McpToolError()

    /** User is not authorized to modify this resource. */
    data class Unauthorized(val resource: String) : McpToolError()

    /** Unexpected programming or infrastructure error — logged, not surfaced to LLM. */
    data class Internal(val text: String, val rootCause: Throwable? = null) : McpToolError()
}

/** Human-readable representation of [McpToolError], safe to include in a tool response. */
fun McpToolError.format(): String = when (this) {
    is McpToolError.Validation -> "[Validation] $field: $text"
    is McpToolError.NotFound -> "[NotFound] $resource '$id' not found"
    is McpToolError.Conflict -> "[Conflict] $reason"
    is McpToolError.Unauthorized -> "[Unauthorized] Cannot modify $resource — belongs to another user"
    is McpToolError.Internal -> "[InternalError] $text"
}
