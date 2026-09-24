# MCP Server

Koog → MCP adapter. Exposes 30 AI tools (SimpleTool implementations) as MCP JSON-RPC tools via stdio transport. Designed for AI agent integration (ZCode, Claude Code, Cursor).

## Build

```bash
./gradlew :mcp-server:build     # Produces mcp-server-jvm-*.jar
```

## Run

```bash
java -jar mcp-server/build/libs/mcp-server-jvm-*.jar --profile=<profile-name>
```

Profiles correspond to data isolation profiles (e.g., `ai-agent`, `personal`). Each profile has its own Room database and Supabase sync state.

## Architecture

```
Main.kt (entry point)
  → ToolRegistrar.kt (Koog SimpleTool → MCP Tool adapter)
  → registers all 30 tools
  → stdin/stdout JSON-RPC loop
```

## Tools exposed

| Category | Tools |
|---|---|
| Task write | `create_task`, `update_task`, `delete_task` |
| Task read | `get_task`, `list_tasks`, `search_tasks`, `list_linked_tasks` |
| Note write | `create_note`, `update_note`, `delete_note` |
| Note read | `get_note` |
| Project write | `create_project`, `update_project`, `delete_project` |
| Project read | `get_project`, `list_projects` |
| Tag write | `create_tag`, `delete_tag` |
| AI Gen | `refine_task`, `smart_rewrite`, `generate_description`, `decompose_task`, `generate_checklist`, `pick_time`, `cluster_tasks`, `cluster_notes`, `project_review`, `weekly_plan`, `improve_note` |
| ADR | `write_adr`, `list_adrs`, `read_adr` |

## Error handling

All errors are MCP-compliant JSON-RPC errors: business errors → `isError: true`, internal errors → `-32603`. See `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/`.
