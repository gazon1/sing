package com.singularity.todo.mcp

import com.singularity.todo.mcp.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations as McpToolAnnotations

/**
 * Extension to convert a [ToolAnnotations][com.singularity.todo.mcp.ToolAnnotations]
 * to the MCP SDK [McpToolAnnotations] type.
 */
fun ToolAnnotations.toMcpAnnotations(): McpToolAnnotations {
    return McpToolAnnotations(
        title = "",
        readOnlyHint = readOnlyHint,
        destructiveHint = destructiveHint,
        idempotentHint = idempotentHint,
        openWorldHint = openWorldHint,
    )
}
