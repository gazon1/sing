---
title: "MCP tool catalog: 32 Koog SimpleTools registered via Koin"
date: 2026-09-18
status: accepted
tags: [ai, mcp, koog, tools, architecture]
---

## Context

The MCP server exposes 32 tools to AI agents (ZCode, Claude Code, Cursor) via the Koog framework. Tools are either **read/write** (operate on data) or **LLM-powered** (call an LLM for generation).

## Tool Registry

All 32 tools are registered as `single<List<Tool<*, *>>>` in `AiToolsDiModule.kt` (Koin DI).

### Read-only tools (12)

| Tool name | Description |
|---|---|
| `get_task` | Fetch a single task by ID |
| `list_tasks` | List tasks with optional status/project/tag filters |
| `search_tasks` | Full-text search across task titles and descriptions |
| `list_linked_tasks` | Tasks linked to a note via internal links |
| `get_note` | Fetch a single note by ID |
| `get_project` | Fetch a single project by ID |
| `list_projects` | List all projects |
| `list_adrs` | List all architecture decision records |
| `read_adr` | Read a single ADR by slug |

### Write/idempotent tools (10)

| Tool name | Description |
|---|---|
| `create_task` | Create a new task |
| `update_task` | Update an existing task |
| `delete_task` | Soft-delete a task |
| `create_note` | Create a new note |
| `update_note` | Update an existing note |
| `delete_note` | Delete a note |
| `create_project` | Create a new project |
| `update_project` | Update an existing project |
| `delete_project` | Delete a project |
| `create_tag` | Create a new tag |

### LLM-powered tools (10)

| Tool name | Model | Description |
|---|---|---|
| `refine_task` | GPT-4o / configured | Rewrite task title + description to be clearer |
| `smart_rewrite` | GPT-4o | Rewrite note body with improved clarity |
| `generate_description` | GPT-4o | Generate a description for a task |
| `decompose_task` | GPT-4o | Break a large task into smaller sub-tasks |
| `generate_checklist` | GPT-4o | Generate a checklist for a task |
| `pick_time` | GPT-4o | Suggest optimal due date/time for a task |
| `cluster_tasks` | GPT-4o | Group tasks by project or topic |
| `cluster_notes` | GPT-4o | Group notes by topic |
| `project_review` | GPT-4o | Review a project and suggest improvements |
| `weekly_plan` | GPT-4o | Generate a weekly plan from open tasks |

### ADR tools (2)

| Tool name | Description |
|---|---|
| `write_adr` | Write or update an ADR in `docs/decisions/` |
| `read_adr` | Read an existing ADR |

### Destructive tools (note: marked as destructive in ToolAnnotations)

| Tool name | Risk |
|---|---|
| `delete_task` | Soft-delete (recoverable via restore) |
| `delete_note` | Permanent deletion |
| `delete_project` | Permanent deletion |
| `delete_tag` | Permanent deletion |

## Registration (Koin)

```kotlin
// AiToolsDiModule.kt
single<List<Tool<*, *>>> {
    listOf(
        GetTaskTool(repo),
        ListTasksTool(repo),
        CreateTaskTool(repo),
        // ... all 32
    )
}
```

**NOT** via `@IntoSet` annotations — pure Kotlin DSL `listOf(...)` inside `single { }`.

## Consequences

- All 32 tools are available to any AI agent via MCP stdio
- Write tools use repositories directly (same layer as ViewModels)
- LLM tools go through the Koog prompt pipeline (`PromptExecutor`)
- `llm_usage` table tracks input/output tokens and cost per call

## Links

- `feature/ai/tools/` — all tool implementations
- `feature/ai/tools/ToolFactories.kt` — LLM tool factory functions
- `mcp-server/` — Koog → MCP adapter layer
