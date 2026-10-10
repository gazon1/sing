package com.singularity.todo.mcp

/**
 * App-level tool annotations — hints to the LLM about tool behaviour.
 *
 * From MCP spec 2025-06-18: these are **hints**, untrusted by spec.
 * Clients like Claude Code show warnings based on these values.
 *
 * To convert to the MCP SDK type, use [toMcpAnnotations] from the mcp-server module.
 */
data class ToolAnnotations(
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = false,
)


