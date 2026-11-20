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

**Process model:** each CLI invocation runs in its own process with an in-memory inspector runtime;
sessions/operations are not shared across separate process invocations. For stateful multi-step
workflows in one process, use `agent journey --file ...` (steps share the same runtime/session
state). For interactive multi-command driving with a long-lived runtime, use the screen inspector
MCP tools on the existing MCP server.

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

### Multi-step CLI workflow (stateful, one process)

```bash
java -jar mcp-server.jar agent journey --file journey.json
```

See the `journey` section below for the step format. Within a journey, `open/describe/snapshot/
actions/invoke/wait/cancel/close` run against the same runtime; `--session` is optional when a
single session is current.

### Command reference (CLI / journey steps)

- `screens` — list supported screens and parameter schemas.
- `sessions` — list open sessions in the current runtime.
- `open --screen <id> [--params <json> | --task-id <id>]` — open a session and return initial snapshot/actions.
- `describe|inspect|snapshot|actions [--session <id>]` — inspect the current or specified session.
- `invoke --action <name> [--input <json>] [--session <id>] [--wait-ms <ms>]` — dispatch an action and observe status; `--wait-ms` waits for completion/failure/timeout within the same invocation.
- `wait --operation <id> [--timeout-ms <ms>] [--session <id>]` — wait for an operation (wait timeout is reported to the caller without terminalizing a still-pending operation).
- `cancel --operation <id> [--session <id>]` — reports `unsupported` for a still-dispatched action (cannot safely stop execution once handed to the real screen path); returns current status if already terminal.
- `close [--session <id>]` — close a session.

Open result (shape):

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

Wait result (when completion/failure is observed):

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

If the wait deadline expires before completion, `wait` returns `status: "timeout"` for that wait
without marking the operation terminal; a subsequent `wait` in the same runtime/journey may still
observe completion or failure.

`status` is one of `accepted|completed|failed|timeout`. Dispatch success means the action was
validated and handed to the real screen/coordinator path; business outcome is observed via
action-correlated state/error signals and surfaced in operation status/snapshot (never by claiming
completion on dispatch alone).

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
- `screen_cancel` — `operationId`, `sessionId?` (unsupported for in-flight dispatched actions; cannot stop execution once accepted).
- `screen_close` — `sessionId?`.

Tool results are JSON payloads (structuredContent) with the same shapes/codes as CLI `result`/`error`.

## Error model (structural, deterministic)

Errors are returned as structured JSON and do not dispatch actions:

- `unknown_screen`, `session_not_found`, `operation_not_found`
- `unknown_action`, `action_not_available`
- `invalid_action_input`, `invalid_arguments`
- `unsupported` (e.g. cancellation of a dispatched action is not supported)
- `operation_timeout` (wait deadline), `operation_failed`
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
- Completion observation is action-correlated: an operation becomes `completed` when verified
  post-dispatch screen state satisfies the action's post-condition (including explicit idempotent
  no-op post-conditions where applicable), not from arbitrary state emissions. A satisfied
  post-condition wins over a later error or the observation deadline, so a finished action is never
  reported as failed. When the post-condition is still unmet, an `Error` event is attributed to the
  operation only if it is one of the failure messages that action's own screen path emits (for
  example `Delete failed` / `Archive failed` for `delete_task`); errors from the task-observation
  flow or from a slot the action never touches are ignored. To keep a dispatched operation from
  staying pending forever — for example an exception thrown before the slot reports, or a silent
  no-op — the session ends an unresolved operation as `failed` at its own observation deadline
  (`OBSERVATION_DEADLINE_MS`, ten seconds). That deadline is separate from a caller's wait timeout,
  which stays non-terminal: a `screen_wait` that reaches its own deadline returns `timeout` without
  terminalizing the operation, and a later `screen_wait` still observes the action's final
  `completed`/`failed`. Cancellation of a dispatched action is not supported because execution cannot
  be safely stopped once handed to the real screen path.

## Verification

- `shared/src/jvmTest/.../mcp/introspection/ScreenInspectorRuntimeTest.kt` exercises real app
  behavior (Koin graph, real coordinator/repos, Room-backed profile DB in temp app dir):
  discover/open/inspect/actions/invoke/wait/verify and structural errors
  (unknown screen/action/input/session/operation).
- Existing canonical adapter tests remain in
  `shared/src/commonTest/.../feature/tasks/presentation/introspection/TaskDetailScreenIntrospectionTest.kt`.
