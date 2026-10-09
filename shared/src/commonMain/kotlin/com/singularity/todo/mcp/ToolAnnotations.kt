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

/**
 * Maps Koog tool descriptor name → app-level [ToolAnnotations].
 *
 * Keys MUST equal the Koog tool descriptor name verbatim (`ToolRegistrar` looks
 * them up as `TOOL_ANNOTATIONS[descriptor.name]`). Until 2026-10-04 the map used
 * dotted names (`tasks.create`) while the registry exposes snake_case
 * (`create_task`) — every entry was silently dead and no tool shipped
 * annotations. `McpServerEndToEndTest` now pins this contract.
 *
 * Keys without a registered tool are intentionally absent.
 */
val TOOL_ANNOTATIONS: Map<String, ToolAnnotations> = mapOf(
    // ── Tasks ──────────────────────────────────────────────────────────────────
    "create_task" to ToolAnnotations(idempotentHint = true),
    "update_task" to ToolAnnotations(idempotentHint = true),
    "delete_task" to ToolAnnotations(destructiveHint = true),
    "list_tasks" to ToolAnnotations(readOnlyHint = true),
    "get_task" to ToolAnnotations(readOnlyHint = true),
    "search_tasks" to ToolAnnotations(readOnlyHint = true),
    "decompose_and_create" to ToolAnnotations(),

    // ── Projects ───────────────────────────────────────────────────────────────
    "create_project" to ToolAnnotations(idempotentHint = true),
    "update_project" to ToolAnnotations(idempotentHint = true),
    "delete_project" to ToolAnnotations(destructiveHint = true),
    "list_projects" to ToolAnnotations(readOnlyHint = true),
    "get_project" to ToolAnnotations(readOnlyHint = true),

    // ── Notes ──────────────────────────────────────────────────────────────────
    "create_note" to ToolAnnotations(idempotentHint = true),
    "update_note" to ToolAnnotations(idempotentHint = true),
    "delete_note" to ToolAnnotations(destructiveHint = true),
    "get_note" to ToolAnnotations(readOnlyHint = true),

    // ── Tags ───────────────────────────────────────────────────────────────────
    "create_tag" to ToolAnnotations(idempotentHint = true),
    "delete_tag" to ToolAnnotations(destructiveHint = true),

    // ── ADR ────────────────────────────────────────────────────────────────────
    "write_adr" to ToolAnnotations(openWorldHint = true),
    "list_adrs" to ToolAnnotations(readOnlyHint = true),
    "read_adr" to ToolAnnotations(readOnlyHint = true),
)
