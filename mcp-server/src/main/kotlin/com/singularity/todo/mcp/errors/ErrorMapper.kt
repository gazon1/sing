package com.singularity.todo.mcp.errors

import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonNull

/**
 * Maps [McpToolError] to [CallToolResult] following the two-tier error model.
 *
 * - [McpToolError.Internal] → thrown as [McpException] (JSON-RPC error -32603)
 * - All other errors → returned as [CallToolResult] with isError = true
 */
object ErrorMapper {
    private const val INTERNAL_ERROR_CODE = -32603
    private val log = Logger.withTag("ErrorMapper")

    fun toResult(error: McpToolError): CallToolResult {
        return when (error) {
            is McpToolError.Internal -> {
                log.e(error.format(), error.cause)
                throw McpException(
                    code = INTERNAL_ERROR_CODE,
                    message = error.format(),
                    data = JsonNull,
                    cause = error.cause,
                )
            }

            is McpToolError.Validation,
            is McpToolError.NotFound,
            is McpToolError.Conflict,
            is McpToolError.Unauthorized -> {
                CallToolResult(
                    content = listOf(TextContent(text = error.format())),
                    isError = true,
                )
            }
        }
    }
}
