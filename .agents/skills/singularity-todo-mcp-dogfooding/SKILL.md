---
name: singularity-todo-mcp-dogfooding
description: How the AI agent should use the Singularity Todo MCP server to track its own multi-step plans via tasks/projects/tags/ADRs. Trigger when the agent is dogfooding inside Singularity Todo (ZCode, Claude Code, Cursor with this MCP wired), needs to track multi-step work as it edits shared/, or needs to commit a design rationale as an ADR. Covers plan-tracking pattern, follow-up tasks discovered mid-edit, and the tooling contracts (create_task, decompose_and_create, write_adr).
---

# Singularity Todo — MCP Dogfooding

## When to use this

You are connected to a Singularity Todo MCP server (40+ tools, stdio transport).
Use it to **track the work you are doing on this codebase** — not as a passive
note, but as a real plan that persists across turns.

Concrete situations:
- You started a multi-step refactor and need to remember the steps across turns.
- You found a follow-up bug while editing a different area → create a follow-up task.
- You are about to make a non-trivial design choice → commit an ADR.
- You need to remember which sub-tasks a parent task has decomposed into.
- A user asks "what did the agent plan?" — read `list_tasks` against the project.

Do NOT use MCP for trivial single-step work (one-line fixes, formatting,
known-mechanical migrations). Use `git commit` messages for that.

## Tool surface (the 30 tools, grouped)

Task lifecycle:
- `create_task(title, …)` — **proposes** a task; the user confirms it. Optional:
  `description, priority, projectId, parentTaskId, tagIds, dueDate, dueTime,
  someday`. The answer carries `proposalCreated` and `proposalId`, not a
  `taskId` — nothing exists until the user says yes.
- `list_tasks(userId?, projectId?, limit?)` — read. Note: server writes under
  the `ai-agent` profile's user (`4a1f6802-…`); list with that userId or
  use `list_tasks({projectId: "..."})` to filter.
- `search_tasks(query, userId?, limit?)` — the app's own search: free text over
  titles, descriptions and tag names, plus `tag:`, `project:`, `priority:`,
  `due:`, `has:description`, `pinned`, and a leading minus that negates a term.
  The same query language the search screen uses.
- `get_task(taskId)` — single record (uses field name `taskId`, not `id`).
- `update_task(taskId, …)` — **proposes** the change; null keeps field, `""`
  clears `dueDate`. The answer carries `proposalCreated` and `proposalId`.
- `delete_task(taskId, reason?)` — creates a **proposal** to archive the task; the
  user confirms it. It does not delete anything on its own.

Projects + tags:
- `create_project(name, color?, icon?, description?, colorHex?)` — **proposes** a
  project; the user confirms it. Pass `colorHex: "#RRGGBB"` (preferred) OR
  `color: 4283215696` (ARGB int).
- `create_tag(name, color?, colorHex?)` — same color contract. This one still
  writes directly; a tag is the vocabulary, not the user's work.
- `list_tasks({projectId: "..."})` ≡ `list_linked_tasks(projectId)` — same query.

Decomposition:
- `decompose_task(title, description?)` — **plan only**. Returns
  `{"subTasks": ["…"]}`. Does NOT write to DB. Use for read-only planning.
- `decompose_and_create(title, description?, priority?, projectId?,
  tagIds?, parentTaskId?)` — **plan + propose in one call**. The answer carries
  `proposalCreated`, `proposalId` and `proposedTaskCount`; the user confirms it.
  When the LLM is unreachable it returns `planSource: "empty"` with nothing
  proposed; fall back to a hand-rolled list and call `create_task` per item.
  Pass `parentTaskId` so the proposed sub-tasks land under it.

Notes:
- `create_note(title?, bodyMarkdown?, isFolder?, parentNoteId?)` — **proposes** a
  note; the user confirms it. Optional everything; default `title = ""`.

ADRs (decisions log):
- `write_adr(slug, title, tags?, body)` — slug is the filename stem
  (e.g. `"2026-09-08-mcp-plan-tracking-via-mcp"`). `title` and `tags`
  go into YAML frontmatter. Body is markdown (`## Idea / ## Decision / ##
  Rationale / ## Consequences`). Writes `status: open` automatically.
  Fails loudly if the slug already exists — use `read_adr` first to check.
- `read_adr(slug)`, `list_adrs()` — list all ADRs; `read_adr` gets one.
- `list_open_deferred()` — lists deferred backlog entries with OPEN/PARTIAL
  status, from `docs/decisions/deferred/` directory (T2 split layout).
- `search_decisions(query, limit?, kind?)` — **find** decisions by title, tags or body
  text. Every hit carries its status, so a decision that is still open is visible
  without opening the file. This is the tool that replaces grepping the repository.
- `search_docs(query, limit?, scope?)` — find the *written* knowledge: the architecture
  documents, the skills, the capability specifications and the decisions. `scope` is one
  of `decisions`, `architecture`, `skills`, `specs`, `all`.

Reading the project's knowledge (prefer the tools over a shell):
- `search_decisions` / `search_docs` for "what did we decide about X?" and "where is the
  rule about X?" — both answer with a path and a snippet.
- An empty result means *nothing matched*. Check the `available` field: `false` means
  there was no corpus to search, which is a different answer.
- The answer carries `schemaVersion`: the search's shape is a contract, and the version
  says which shape you are reading. A field you do not know is a newer server, not a
  shape you are misreading.

Describing the toolbox itself:
- `describe_toolbox()` — which tools this server exposes, which are switched off by the
  remote configuration, which build and checkout it is, and whether the configuration was
  actually applied. Call it when a tool you expected is missing.
- `describe_toolbox(includeDisabled=true)` adds the disabled tools. `unmatchedFlags` is
  a third list: remote-configuration flags that named **no** tool. Such a flag switched
  nothing off — so if a tool you expected to be gone is still there, the flag is
  probably the reason, and this is where you see it.
- `toolset.surfaceContractVersion` says which version of the surface contract this
  server speaks. The payload may only grow, so a newer server is fine — but if you read
  a field you do not know, the version tells you whether that is a field added later or
  a shape you are misreading.
- The read-only documents are also served as MCP resources: `singularity://toolbox`,
  `singularity://decisions`, `singularity://decision/{slug}`. Client support for
  resources varies; the tools above are the portable path.

## Standard workflows

### Workflow A — Start a new multi-step task

```
1. create_project(name="MCP dogfooding — <topic>")
   → proposes the project; the user confirms it, and the proposal names the
     id the project will have — store that projectId
2. create_tag(name="mcp-<topic>", colorHex="#4CAF50")
   → store tagId (this one writes directly)
3. create_task(
     title="<root task>",
     description="<what + how to verify>",
     priority="High",
     projectId=projectId,
     tagIds=[tagId],
   )
   → proposes the task; the user confirms it, and the proposal names the id
     the task will have — store that topTaskId
4. decompose_and_create(
     title=<same root title>,
     parentTaskId=topTaskId,
     projectId=projectId,
     tagIds=[tagId],
   )
   → proposes every sub-task of the decomposition
   If planSource=="empty" (LLM down): hand-roll a sub-task list and call
   create_task(...) per sub-task with the same projectId + tagIds +
   parentTaskId.
```

### Workflow B — Found a follow-up mid-edit

```
1. create_task(
     title="Followup: <found problem>",
     priority="Low" or "Medium",
     projectId=<current project id, or new one>,
     tagIds=[<current tag id, or new one>],
   )
2. Continue the original work. The follow-up lives in DB independent
   of the current turn.
```

### Workflow C — Made a non-trivial design choice

```
1. write_adr(
     slug="<YYYY-MM-DD>-<short-slug>",
     title="<decision title>",
     tags=["<area>", "<tag>"],
     body="## Idea\n<...>\n\n## Decision\n<...>\n\n## Rationale\n<...>\n\n## Consequences\n<...>\n",
   )
2. Then run scripts/refresh-decisions-digest.sh locally so
   docs/decisions/DIGEST.md picks up the new ADR.
```

### Workflow D — Read state to ground a new turn

```
1. list_tasks({projectId: <known project>, limit: 50})
   → context: what's already done, what's left
2. search_tasks(query="<open question or topic>")
   → catch any existing work on the same topic
3. list_adrs({limit: 10})
   → recent design decisions relevant to this turn
```

## Sub-task linking — `parent_task_id`

As of schema v10 (Room migration v9 → v10), `Task.parent_task_id` is a real
nullable column. MCP tools that support it: `create_task`, `decompose_and_create`.

If you forget to pass `parentTaskId`, sub-tasks created via the
loop-after-`decompose_task` pattern fall back to the **shared `projectId` + `tagIds`**
link that worked before. Both are queryable: `list_tasks({projectId:...})`
filters both old-style and new-style sub-tasks the same way.

## Colours (hex helper, C2 fix)

`create_project` and `create_tag` both accept:
- `colorHex: "#RRGGBB"` → 0xFFRRGGBB
- `colorHex: "#AARRGGBB"` → 0xAARRGGBB
- `colorHex: "RRGGBB"` (no `#`) → same as #RRGGBB
- `color: 4283215696` (decimal ARGB long) → narrowed to Int
- `color` omitted → `0xFF2196F3` (blue) for projects, `0xFF9E9E9E` (grey) for tags

Always prefer `colorHex` — Int values overflow signed 32-bit ARGB integers.

## LLM-unavailable fallback

The ai-agent profile typically does NOT have an OpenAI API key in
SecureStorage. As a result all `*-tool-uses-promptExecutor` calls return
`[InternalError]`. Two safe patterns:

1. Use `decompose_and_create` — it catches the failure and returns
   `planSource: "empty"` with nothing proposed. Then you provide your own
   sub-task list and call `create_task` per item.
2. Read tools directly with `search_tasks`, `list_tasks`, `list_adrs` —
   these are pure DB reads and work without any LLM.

If a tool returns `[InternalError]`, do NOT retry the same call. Compose
your own response from inputs already on the DB.

## Hard rules

1. **No `println` to stdout** — that is the MCP JSON-RPC channel. Use
   `System.err` (your tool's logger) for diagnostic logs. Never write
   diagnostic output via `print`.
2. **One MCP call → one round trip.** Don't batch 20 `create_task`s per
   frame; loop sequentially.
3. **`userId` is server-side** in writes (driven by `ProfileAwareCurrentUser`).
   Pass `userId` in **reads** because `list_tasks` defaults to `"local-user"`,
   which is NOT what ai-agent writes under. Use `list_tasks({projectId: "..."})`
   to filter without userId at all.
4. **Dispose tasks after smoke tests** — when verifying, delete test rows
   with `delete_task` so they don't pollute the persistent plan.
5. **Do not promote a plan to source-of-truth.** The MCP creates real DB
   rows — for the tools that write, and for the proposals the user confirms.
   Errors propagate: a typo in `projectId` → `Task.projectId`
   becomes `null` silently. Validate by `list_tasks` after writes.
6. **A proposal is not a row.** `create_task`, `update_task`, `create_project`,
   `create_note` and `decompose_and_create` propose; the user confirms. Say
   "proposed", not "created" — and do not go looking for a task id the answer
   did not carry.
6. **`get_task` uses `taskId` not `id`** — the decompiled signature says
   `callTool(name="get_task", arguments={taskId: "..."})`. The earlier
   iteration of this skill used `id` and got `[InternalError] Field 'taskId'
   is required`. Always use `taskId`.

## Files in this dogfooding surface

- `mcp-server/.../Main.kt` — bootstrap, blocking lifecycle
- `mcp-server/.../ToolRegistrar.kt` — Koog → MCP dispatch
- `mcp-server/.../ToolAnnotations.kt` — per-tool MCP annotations
- `shared/.../feature/ai/tools/*Tool.kt` — every registered tool
- `shared/.../feature/ai/tools/CreateTaskTool.kt` — primary task write
- `shared/.../feature/ai/tools/DecomposeAndCreateTool.kt` — plan+create
- `shared/.../feature/ai/tools/ColorInput.kt` — hex parser
- `shared/.../core/database/AppDatabase.kt` — version 10

## Related

- `docs/architecture/mcp-server.md` — how to host the server itself
- ADR `2026-09-07-mcp-stdio-blocking-lifecycle` — why the server must
  wrap `createSession` in `done.join()`
- ADR `2026-09-07-mcp-tool-error-model` — business vs internal errors
- ADR `2026-09-08-mcp-plan-tracking-via-mcp` — first C1 end-to-end run
