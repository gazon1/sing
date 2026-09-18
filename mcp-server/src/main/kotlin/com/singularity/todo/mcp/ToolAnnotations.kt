package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations as McpToolAnnotations

/**
 * App-level tool annotations — hints to the LLM about tool behavior.
 *
 * From MCP spec 2025-06-18: these are **hints**, untrusted by spec.
 * Clients like Claude Code show warnings based on these values.
 */
data class ToolAnnotations(
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = false,
) {
    /** Converts to the MCP SDK [McpToolAnnotations] type. */
    fun toMcpAnnotations(): McpToolAnnotations {
        return McpToolAnnotations(
            title = "",
            readOnlyHint = readOnlyHint,
            destructiveHint = destructiveHint,
            idempotentHint = idempotentHint,
            openWorldHint = openWorldHint,
        )
    }
}

/**
 * Maps tool name → app-level [ToolAnnotations].
 *
 * Populated from the tool naming convention used in the app's Koog registry.
 */
val TOOL_ANNOTATIONS: Map<String, ToolAnnotations> = mapOf(
    // ── Tasks ──────────────────────────────────────────────────────────────────
    "tasks.create" to ToolAnnotations(idempotentHint = true),
    "tasks.update" to ToolAnnotations(idempotentHint = true),
    "tasks.complete" to ToolAnnotations(idempotentHint = true),
    "tasks.delete" to ToolAnnotations(destructiveHint = true),
    "tasks.restore" to ToolAnnotations(),
    "tasks.list" to ToolAnnotations(readOnlyHint = true),
    "tasks.get" to ToolAnnotations(readOnlyHint = true),
    "tasks.search" to ToolAnnotations(readOnlyHint = true),
    "task.set_dependencies" to ToolAnnotations(idempotentHint = true), // MR-1

    // ── Projects ───────────────────────────────────────────────────────────────
    "projects.create" to ToolAnnotations(idempotentHint = true),
    "projects.update" to ToolAnnotations(idempotentHint = true),
    "projects.delete" to ToolAnnotations(destructiveHint = true),
    "projects.list" to ToolAnnotations(readOnlyHint = true),
    "projects.get" to ToolAnnotations(readOnlyHint = true),

    // ── Notes ──────────────────────────────────────────────────────────────────
    "notes.create" to ToolAnnotations(idempotentHint = true),
    "notes.update" to ToolAnnotations(idempotentHint = true),
    "notes.delete" to ToolAnnotations(destructiveHint = true),
    "notes.list" to ToolAnnotations(readOnlyHint = true),
    "notes.get" to ToolAnnotations(readOnlyHint = true),

    // ── Tags ──────────────────────────────────────────────────────────────────
    "tags.create" to ToolAnnotations(idempotentHint = true),
    "tags.assign" to ToolAnnotations(),
    "tags.list" to ToolAnnotations(readOnlyHint = true),

    // ── AI + ADR ───────────────────────────────────────────────────────────────
    "adr.write" to ToolAnnotations(openWorldHint = true),
    "adr.list" to ToolAnnotations(readOnlyHint = true),
    "adr.read" to ToolAnnotations(readOnlyHint = true),
    "decompose_and_create" to ToolAnnotations(),
)
