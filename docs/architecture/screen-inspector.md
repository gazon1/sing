# Headless screen inspector and action driver

> **Reference, not a procedure.** Describes the local headless screen inspector/driver
> delivered for [#3041](https://github.com/gazon1/sing/issues/3041). Update this file in the
> same commit as behavior changes.

## Scope

A local, agent-facing transport over the canonical screen-introspection contract
(`core/llm/ScreenIntrospection`, `SCREEN_INTROSPECTION_CONTRACT_VERSION`): discover supported
screens, open a screen session, inspect allowlisted state/focus/actions, invoke named actions on
the real screen path, wait for completion, and verify resulting state. It is a transport/client
layer only — no business rules, no direct repository writes, no duplicate domain tools, and no
second MCP server.

No production remote-control endpoint. Runs only as local development/test tooling (MCP
server / CLI process with explicit profile selection).

## Host and CLI shape

Initial host is existing repository tooling, per ADR
`2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration.md` (#3046): the inspector
reuses the MCP server runtime (`shared/src/jvmMain/.../mcp`) and is exposed both as MCP tools on
the existing `:mcp-server` / desktop `--mcp-server` server and as a JSON CLI mode of the same
entrypoint. No standalone executable is added (no demonstrated need beyond existing tooling).

CLI entry (same binaries/process model as MCP server):

```bash
# via MCP server jar
java -jar mcp-server/build/libs/mcp-server.jar agent <command> [args]

# via desktop launcher in tool/agent mode
./path/to/singularity-todo agent <command> [args]
```

`--profile-id=<id>` / `--profile=<name>` are honored for data isolation/bootstrap (same contract
as MCP server). All commands print machine-readable JSON to stdout and exit 0 on success, 2 on
structural/usage/operation failure reported as JSON, 1 on internal failure.

## CLI commands (JSON)

All responses are JSON objects: `{"ok":true,"result":...}` or
`{"ok":false,"error":{"code":"...","message":"...","details":{...}?}}`.

### Discover capabilities

```bash
java -jar mcp-server.jar agent screens
```

```json
{
  "ok": true,
  "result": {
    "screens": [
      {
        "id": "tasks.task-detail",
        "description": "Inspect and drive the Task Detail screen headlessly via its canonical introspection adapter.",
        "params": { "type": "object", "properties": { "taskId": { "type": "string", "description": "Task id to open." } }, "required": ["taskId"], "additionalProperties": false }
      }
    ]
  }
}
```

### Open a session

```bash
java -jar mcp-server.jar agent open --screen tasks.task-detail --task-id <TASK_ID>
# or: --params '{"taskId":"<TASK_ID>"}'
```

```json
{
  "ok": true,
  "result": {
    "sessionId": "session-...",
    "screen": "tasks.task-detail",
    "params": { "taskId": "<TASK_ID>" },
    "description": "Inspect and drive the Task Detail screen headlessly via its canonical introspection adapter.",
    "snapshot": { "screen": "tasks.task-detail", "sessionId": "session-...", "params": { "taskId": "<TASK_ID>" }, "status": "loaded", "subject": { "...": "allowlisted fields" } },
    "actions": { "screen": "tasks.task-detail", "sessionId": "session-...", "status": "loaded", "actions": [ { "name": "set_priority", "available": true, "input": { "...": "schema" } } ] }
  }
}
```

### Inspect / describe / actions

```bash
java -jar mcp-server.jar agent inspect --session <SESSION_ID>
java -jar mcp-server.jar agent describe --session <SESSION_ID>
java -jar mcp-server.jar agent actions --session <SESSION_ID>
```

`inspect`/`snapshot` return the allowlisted snapshot (screen/session/params/status/subject and
focus when present). `describe` returns contract metadata + status + actions (with availability
and exact input schemas) + focus. `actions` returns status/focus/actions. `--session` is optional
when exactly one session is open.

### Invoke and wait

```bash
java -jar mcp-server.jar agent invoke --action set_priority --input '{"priority":"high"}' --session <SESSION_ID>
java -jar mcp-server.jar agent wait --operation <OPERATION_ID> --timeout-ms 30000 --session <SESSION_ID>
# convenience: invoke and wait in one call
java -jar mcp-server.jar agent invoke --action toggle_complete --wait-ms 5000 --session <SESSION_ID>
```

Invoke result (accepted dispatch vs observed completion):

```json
{
  "ok": true,
  "result": {
    "operationId": "op-...",
    "action": "set_priority",
    "status": "accepted",
    "message": "Action accepted; completion not yet observed.",
    "snapshot": { "status": "loaded", "subject": { "priority": "none", "...": "..." } }
  }
}
```

Wait result:

```json
{
  "ok": true,
  "result": {
    "operationId": "op-...",
    "action": "set_priority",
    "status": "completed",
    "snapshot": { "status": "loaded", "subject": { "priority": "high", "...": "..." } }
  }
}
```

`status` is one of `accepted|completed|failed|cancelled|timeout`. Dispatch success means the action
was validated and handed to the real screen/coordinator path; business outcome is observed via
state progression/events and surfaced in operation status/snapshot (never by claiming completion on
dispatch alone).

### Cancel / close / sessions

```bash
java -jar mcp-server.jar agent cancel --operation <OPERATION_ID> --session <SESSION_ID>
java -jar mcp-server.jar agent close --session <SESSION_ID>
java -jar mcp-server.jar agent sessions
```

### Multi-step journey (scriptable)

```bash
java -jar mcp-server.jar agent journey --file journey.json
```

`journey.json`:

```json
{
  "steps": [
    { "name": "open", "op": "open", "screen": "tasks.task-detail", "params": { "taskId": "<TASK_ID>" } },
    { "name": "inspect", "op": "snapshot" },
    { "name": "prioritize", "op": "invoke", "action": "set_priority", "input": { "priority": "high" }, "waitMs": 5000 },
    { "name": "verify", "op": "snapshot" },
    { "name": "close", "op": "close" }
  ]
}
```

Journey result is deterministic JSON with per-step ok/duration/result or error; execution stops on
first failure unless `continueOnError: true` on a step.

## MCP tools (same runtime, existing server)

Registered on the existing MCP server (no domain-tool duplication, no second server):

- `screen_list` — supported screens/capabilities and parameter schemas.
- `screen_sessions` — open sessions/current session.
- `screen_open` — `screen`, `params` → session, snapshot, actions.
- `screen_describe` / `screen_snapshot` / `screen_actions` — `sessionId?`.
- `screen_invoke` — `action`, `input?`, `sessionId?` → `operationId`, status, snapshot.
- `screen_wait` — `operationId`, `timeoutMs?`, `sessionId?` → status, snapshot.
- `screen_cancel` — `operationId`, `sessionId?`.
- `screen_close` — `sessionId?`.

Tool results are JSON payloads (structuredContent) with the same shapes/codes as CLI `result`/`error`.

## Error model (structural, deterministic)

Errors are returned as structured JSON and do not dispatch actions:

- `unknown_screen`, `session_not_found`, `operation_not_found`
- `unknown_action`, `action_not_available`
- `invalid_action_input`, `invalid_arguments`
- `operation_timeout`, `operation_failed`, `operation` cancelled state
- `internal` (unexpected)

Validation/availability are enforced by the canonical adapter/contract before dispatch
(allowlist, exact schema/`additionalProperties:false`, availability requirements).

## Implementation notes

- Runtime: `shared/src/jvmMain/.../mcp/introspection/` (`ScreenInspectorRuntime`,
  `TaskDetailScreenSession`, `ScreenRegistry`, `OperationTracker`, `ScreenInspectorTools`,
  `ScreenInspectorCli`). Sessions drive the real `TaskDetailCoordinator` and
  `TaskDetailScreenIntrospection` via DI-resolved dependencies.
- Initial supported screen: `tasks.task-detail` (reference adapter; actions per canonical
  contract: `set_priority`, `add_checklist_item`, `toggle_complete`, `delete_task`, `restore_task`).
- Completion observation is based on post-dispatch screen state/error signals; `screen_wait`
  reports timeout deterministically when completion is not observed within the window.

## Verification

- `shared/src/jvmTest/.../mcp/introspection/ScreenInspectorRuntimeTest.kt` exercises real app
  behavior (Koin graph, real coordinator/repos, Room-backed profile DB in temp app dir):
  discover/open/inspect/actions/invoke/wait/verify and structural errors
  (unknown screen/action/input/session/operation).
- Existing canonical adapter tests remain in
  `shared/src/commonTest/.../feature/tasks/presentation/introspection/TaskDetailScreenIntrospectionTest.kt`.
