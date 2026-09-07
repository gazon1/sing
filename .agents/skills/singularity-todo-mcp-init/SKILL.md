---
name: singularity-todo-mcp-init
description: MCP init command pattern for Singularity Todo KMP. Use when the MCP server starts and needs to register its tools in the workspace (AGENTS.md, ZCode config, .mcp/mcp.json). Covers auto-generating a tool manifest, updating AGENTS.md section, and profile-aware startup. Based on beads bd init pattern.
---

# MCP Init — Auto-Discovery for External Agents

## Overview

When the MCP server starts for the first time in a workspace, it should help the human (and the AI agent) understand what's available. The `bd init` command in Beads creates/updates `AGENTS.md` with instructions for AI agents. We do the same: on first run, `mcp-server --init` writes a tool manifest and appends to `AGENTS.md`.

This enables **agent auto-discovery** — Claude Code, ZCode, and other MCP clients can read the manifest and know exactly which tools are available without manual configuration.

## When to Use This Skill

- The MCP server needs to register itself in a workspace on first run.
- You want AI agents to automatically discover available tools without reading source code.
- The human wants a quick reference of available MCP tools.
- Setting up a new project profile.

Skip for: automated CI runs, smoke tests, or when the workspace already has a manifest.

## `mcp-server --init` Command

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/init/McpInitCommand.kt
package com.singularity.todo.mcp.init

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class McpInitCommand(
    private val workspaceRoot: Path,
    private val tools: List<ToolInfo>,
    private val profileName: String,
) {
    /**
     * Run `mcp-server --init` to:
     * 1. Write .mcp/mcp.json (MCP manifest)
     * 2. Append MCP tools section to AGENTS.md
     * 3. Print next steps
     */
    fun run(): InitResult = runBlocking {
        val manifestResult = writeManifest()
        val agentsResult = appendToAgentsMd()
        InitResult(
            manifestWritten = manifestResult,
            agentsAppended = agentsResult,
        )
    }

    private fun writeManifest(): Path {
        val mcpDir = workspaceRoot.resolve(".mcp")
        Files.createDirectories(mcpDir)
        val manifest = mcpDir.resolve("mcp.json")
        val content = buildJsonString {
            put("version", "1.0")
            put("name", "singularity-todo")
            put("profile", profileName)
            putArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description.take(200))
                        put("readOnly", tool.isReadOnly)
                        put("destructive", tool.isDestructive)
                    }
                }
            }
        }
        Files.writeString(manifest, content)
        return manifest
    }

    private fun appendToAgentsMd(): Path {
        val agentsFile = workspaceRoot.resolve("AGENTS.md")
        val section = buildString {
            appendLine("## MCP Server — Singularity Todo")
            appendLine()
            appendLine("**Profile:** `$profileName`")
            appendLine()
            appendLine("**MCP Server:** `./gradlew :mcp-server:run --quiet --args=--profile=$profileName`")
            appendLine()
            appendLine("### Available Tools")
            appendLine()
            appendLine("| Tool | Description | Read-only? |")
            appendLine("|------|-------------|------------|")
            tools.forEach { tool ->
                val desc = tool.description.replace("|", "\\|").take(60)
                appendLine("| `${tool.name}` | $desc | ${if (tool.isReadOnly) "✅" else "❌"} |")
            }
            appendLine()
            appendLine("### ZCode Configuration")
            appendLine()
            appendLine("Add to `~/.zcode/mcp/servers.toml`:")
            appendLine("```toml")
            appendLine("[[servers]]")
            appendLine("name = \"singularity-todo-$profileName\"")
            appendLine("command = [\"./gradlew\", \":mcp-server:run\", \"--quiet\", \"--args=--profile=$profileName\"]")
            appendLine("```")
            appendLine()
        }

        val existing = if (Files.exists(agentsFile)) Files.readString(agentsFile) else ""
        val marker = "<!-- MCP_SERVER_END -->"
        val updated = if (existing.contains("## MCP Server — Singularity Todo")) {
            // Replace existing section
            existing.replace(Regex("## MCP Server — Singularity Todo.*?(?=## |\$)", RegexOption.DOT_MATCHES_ALL), section.trim() + "\n\n")
        } else {
            // Append
            if (existing.endsWith("\n")) existing else existing + "\n" + section
        }

        Files.writeString(agentsFile, updated)
        return agentsFile
    }
}

data class ToolInfo(
    val name: String,
    val description: String,
    val isReadOnly: Boolean,
    val isDestructive: Boolean,
)
```

## `--init` vs `--profile`

Both are `--args` flags passed to `main()`:

| Flag | Purpose |
|---|---|
| `--init` | Write manifest + update AGENTS.md. Exit immediately after. |
| `--profile=NAME` | Start MCP server with profile `NAME` active. |
| `--profile=NAME --init` | Both: start with profile AND write manifest. |

```kotlin
fun main(args: Array<String>): Unit = runBlocking {
    val profileArg = args.argOrNull("--profile") ?: "default"
    val shouldInit = args.has("--init")
    val profileId = resolveProfile(profileArg)

    if (shouldInit) {
        val tools = discoverTools()
        val cmd = McpInitCommand(workspaceRoot, tools, profileArg)
        val result = cmd.run()
        System.err.println("""
            |✅ MCP manifest written to: ${result.manifestWritten}
            |✅ AGENTS.md updated at: ${result.agentsAppended}
            |
            |Next steps:
            |  1. Add to ~/.zcode/mcp/servers.toml (see AGENTS.md)
            |  2. Restart ZCode / Claude Code
            |  3. Run: ./gradlew :mcp-server:run --quiet --args=--profile=$profileArg
        """.trimMargin())
        return@runBlocking
    }

    // Normal server start...
}
```

## .mcp/mcp.json — Standard MCP Manifest

The `.mcp/mcp.json` file is recognized by some MCP clients (Claude Desktop, Cursor) as a workspace-level manifest:

```json
{
  "version": "1.0",
  "name": "singularity-todo",
  "profile": "ai-agent",
  "tools": [
    {
      "name": "tasks.create",
      "description": "Create a new task with title, optional description, priority, and project.",
      "readOnly": false,
      "destructive": false
    },
    {
      "name": "tasks.list",
      "description": "List user's tasks with optional filters (status, project, dueBefore).",
      "readOnly": true,
      "destructive": false
    },
    {
      "name": "tasks.delete",
      "description": "Soft-delete a task (move to trash). dryRun=true by default.",
      "readOnly": false,
      "destructive": true
    }
  ]
}
```

**Note:** This is not part of the official MCP spec but is recognized by Beads and some MCP clients. Include it for compatibility.

## Beads `bd init` Comparison

| Beads | Our MCP-server |
|---|---|
| `bd init` | `mcp-server --init` |
| `AGENTS.md` auto-update | `AGENTS.md` section appended |
| `.beads/` dir | `.mcp/mcp.json` manifest |
| `--server` / `--stealth` modes | `--profile=NAME` |
| `bd setup claude` | ZCode config in `~/.zcode/mcp/servers.toml` |
| ContextVar per workspace | `profileId` passed via `--args` |

## Profile-Aware Manifest

When `--init` is called with a profile, the manifest and AGENTS.md section are profile-specific:

```
AGENTS.md
├── ## MCP Server — Singularity Todo
│   └── **Profile:** `ai-agent`
│   └── Tool list (ai-agent specific)
│   └── ZCode config pointing to --profile=ai-agent
│
└── ## MCP Server — Singularity Todo (personal)
    └── **Profile:** `personal`
    └── Tool list (personal specific)
    └── ZCode config pointing to --profile=personal
```

Users can have **two MCP servers** in ZCode simultaneously — one for `ai-agent` profile, one for `personal`. Tools from both are available.

## Common Mistakes

```kotlin
// ❌ WRONG — writing manifest to current dir instead of workspace root
val manifest = Path.of(".mcp/mcp.json")

// ✅ CORRECT — use workspace root (project directory)
val manifest = workspaceRoot.resolve(".mcp/mcp.json")

// ❌ WRONG — --init runs server (blocks forever)
if (args.has("--init")) { runServer(); return }

// ✅ CORRECT — --init exits immediately
if (args.has("--init")) { runInit(); return@runBlocking }

// ❌ WRONG — appending without checking for duplicate section
Files.writeString(agentsFile, existing + section)

// ✅ CORRECT — replace existing section if present
existing.replace(Regex("## MCP Server.*?(?=## |\$)", ...), section)

// ❌ WRONG — tool description > 500 chars
put("description", "This tool creates a new task...") // too long

// ✅ CORRECT — truncate at 200 chars
put("description", tool.description.take(200))
```

## Related Skills

- `singularity-todo-mcp-server` — the main server that calls `McpInitCommand`.
- `singularity-todo-multi-profile` — how `profileId` is resolved and used.
- `singularity-todo-cli-tool-surface` — tool metadata (readOnly, destructive) comes from annotations.
- `singularity-todo-decisions-workflow` — the AGENTS.md format follows the same conventions as decision entries.
- Beads `AGENTS.md` pattern — inspiration for this skill.
