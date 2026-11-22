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
server / CLI process with explicit profile selection; the MCP-server host is additionally compiled
in by an explicit build property and opted into with an explicit process flag).

## Host and CLI shape

Initial host is existing repository tooling, per ADR
`2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration.md` (#3046): the inspector
reuses the MCP server runtime (`shared/src/jvmMain/.../mcp`) and is exposed both as MCP tools on
the existing `:mcp-server` / desktop `--mcp-server` server and as a JSON CLI mode of the same
entrypoint. No standalone executable is added (no demonstrated need beyond existing tooling).

The MCP-server host is opt-in twice over: the build compiles the capability in only with
`-Psingularity.inspector.host.enabled=true`, and the process registers the screen inspector tools
only when it was started with `--inspector` (`McpServerCommandProvider.INSPECTOR_HOST_FLAG`).
Without either one no host starts. A release build passes neither property nor flag, so the host
is absent there and no argument can turn it on. The build gate is enforced rather than assumed:
the packaging tasks of `:desktopApp` and `:mcp-server` refuse to produce an artefact with the
capability compiled in, so `-P` cannot turn a release package into a host. The CLI host needs no
flag of its own; it is entered through an explicit command. See ADR
`2026-11-20-the-local-screen-inspector-host-is-explicit.md`.

### What the host attaches to — and what it does not

The host is the tool-server **process**. It builds its own Koin container from the
application's real module list and opens its own `TaskDetailCoordinator`, whose actions reach the
real `TaskDetailCoordinator.onIntent`. The contract and the behavior path are the application's,
not a fixture's.

It is **not** the window the user has open. A running Desktop window and `--mcp-server` are two
processes with two graphs and two screen sessions, so the host cannot observe transient on-screen
state (an unsaved editor buffer, for instance). An agent driving the host operates *a* real
session of the screen, not the one currently rendered. Attaching to the live window would need a
local-only transport into the GUI process or an in-process host, which #3042 does not build. See
ADR `2026-11-21-the-host-inspects-a-real-graph-session-not-the-window.md`.

### Android

The logical contract is `commonMain` and the Task Detail adapter is shared, but the inspector
runtime and its host start-up are `jvmMain`. Android debug/test does not expose a host yet: that
slice needs its own lifecycle seam and a gate that cannot ship, and it is tracked separately in
[#3074](https://github.com/gazon1/sing/issues/3074). Issue #3042 is not complete until it lands.

CLI entry (same binaries/process model as MCP server). The CLI is a host too, so the build
capability applies: a build compiled without `-Psingularity.inspector.host.enabled=true` answers
`unsupported` and exits 2 instead of running a command. It needs no `--inspector` flag of its own —
entering the command is the opt-in.

```bash
# via MCP server jar
java -jar mcp-server/build/libs/mcp-server.jar agent <command> [args]

# via desktop launcher in tool/agent mode
./path/to/singularity-todo agent <command> [args]
```

`--profile-id=<id>` / `--profile=<name>` are honored for data isolation/bootstrap (same contract
as MCP server). All commands print machine-readable JSON to stdout and exit 0 on success, 2 on
structural/usage/operation failure reported as JSON, 1 on internal failure.

For the MCP-server host itself (the long-lived runtime an agent drives through the `screen_*` tools),
build with the capability compiled in and pass the flag to the process:

```bash
# Development run: the Gradle property compiles the host in; --inspector opts this process in.
./gw -Psingularity.inspector.host.enabled=true :mcp-server:run \
  --args="--inspector --profile-id=<id>"

# Fat jar built with the capability, then started with the opt-in.
./gw -Psingularity.inspector.host.enabled=true :mcp-server:jar
java -jar mcp-server/build/libs/mcp-server.jar --inspector --profile-id=<id>

# Desktop launcher in tool-server mode (development build with the capability).
./gw -Psingularity.inspector.host.enabled=true :desktopApp:run \
  --args="--mcp-server --inspector --profile-id=<id>"
```

The property is a **build** input, not a JVM property: a release distribution built without it
cannot host the inspector however it is launched.

### Which artefacts are guarded, and which are not

The guard covers the tasks that produce something a user installs: `package*` and
`createDistributable*` in `:desktopApp`, and `distZip`/`distTar`/`installDist` in `:mcp-server`.

`:mcp-server:jar` is deliberately **not** guarded. It is an executable fat JAR, but it is a
`build/libs` development output: `McpServerEndToEndTest` spawns it, and a host-enabled development
build is exactly what it is for. That is safe only while the release workflow publishes no
`:mcp-server` artefact at all — which it does not, and which
`scripts/tests/test_inspector_release_boundary.py` asserts. If release ever starts shipping the fat
JAR, that test fails and the JAR task has to be guarded too: an unguarded executable artefact is a
release path the moment somebody publishes it.

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
- Second adapter, **not** registered with the host: `agenda.tasks`
  (`AgendaScreenIntrospection`; actions `open_task`, `toggle_complete`, `select_task`; the project
  is an optional parameter, so the Inbox tab and a project's list are one screen). It is reached
  by the cross-screen journeys; a host session over it is separate work, see the ADR named above.
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
- `shared/src/jvmTest/.../mcp/InspectorHostOptInTest.kt` pins the host opt-in: without the flag no
  host starts, with the flag the host may run. It also pins the registration seam: no `screen_*`
  tool is registered without the opt-in, and `screen_list` is registered with it.

### Cross-screen journeys (#3043)

`shared/src/commonTest/.../feature/journey/ScreenJourneyTest.kt` drives more than one screen per
test, over the application's real ViewModels and the existing fakes — no emulator, no Compose
rendering, no wall-clock sleep:

| Journey | Screens | What it proves |
|---|---|---|
| list → detail → change → list | `agenda.tasks` (Inbox) → `tasks.task-detail` → back to the list | the change reaches the repository *and* the next list render |
| project list → task → complete | `agenda.tasks` (project) → `tasks.task-detail` | the list is project-scoped: another project's task is refused, not dispatched |
| complete + create | `agenda.tasks` + the real task editor | a completion and a created task both appear in the list |

`JourneyScreens.kt` is the fixture: one `SlotFakes` aggregate behind every screen, so the agenda,
the coordinator and the editor read and write the same `FakeTaskRepository`. That shared store is
what makes a journey a journey rather than three independent screen tests.

**Navigation is published, not performed.** `ScreenState.navigation` carries the destination the
screen itself decided on — the value its click handler would hand the application's navigator — so
a journey opens the next screen from the list's own answer rather than from an id the test made
up. The driver only reads it; nothing dispatches navigation. The contract version moved to 2 with
this field. See ADR `2026-11-22-cross-screen-journeys-and-published-navigation.md`.

**The second adapter does not change the contract's semantics.** `AgendaScreenIntrospection`
(`agenda.tasks`, the project as a parameter) supplies a snapshot projection, an intent mapping and
an availability rule; the allow-list, the schema check and validate-before-dispatch still come
from `ScreenIntrospection`. A negative control confirms the journeys measure the application: with
`dispatch` reduced to a no-op, exactly the three dispatch journeys fail and the three
contract/refusal journeys still pass.

### UI wiring is not what the headless tests verify

The screen-behavior tests above drive the adapter. None of them touches a Compose
control, so a control whose `onClick` goes nowhere passes all of them: the adapter
reaches `TaskDetailCoordinator.onIntent`, and the control is a different call site.
(The host opt-in test is not one of these — it pins registration, not screen behavior.)

The same boundary applies to the journeys: they say the contract, the dispatcher and each
screen's intent path agree. They do **not** say that a Compose control is wired to a published
action, that every UI-visible value is in a snapshot, or that anything renders — and a passing
journey must not be described as end-to-end UI correctness. That half is #3049.

`desktopApp/src/jvmTest/.../feature/flows/tasks/TaskDetailCompletionWiringTest.kt` is
the missing half for the Task Detail's completion control — a tap on the real control,
the write it must produce, and the state the screen renders from that write. Its own
KDoc names the boundary; the parts worth knowing here:

- **Verified:** `toggle_complete` through the hero's control, in both directions
  (complete → reopen), asserted on the repository write *and* on the rendered
  completion date. Removing the control's callback makes both tests fail, so they
  measure the wiring and not the adapter's own behaviour.
- **Covered by a flow already, not repeated:** the priority row → picker → relabel
  (`SetPriorityFlowTest`) and the checklist inline add-row
  (`TaskDetailEmptyChildSectionsTest`, `TaskItemListScenarioTest`). They assert rendered
  state without reading the write back.
- **Shared dispatch boundary, structurally only:** the tap builds
  `TaskDetailIntent.Domain.ToggleComplete` and `describe()` publishes `toggle_complete`,
  and `TaskDetailScreenIntrospection.intentFor` maps one to the other. That both paths
  *end* at `TaskDetailCoordinator.onIntent` is a fact about the call sites; no test
  compares the constructed values, because neither desktop harness hands out the
  coordinator instance the composition owns and exposing it would be a test-only seam.
  So the tests do not prove the write went through `onIntent` — a callback that wrote to
  the repository directly, with the same resulting state, would pass them. That matters
  for what the slots add on the way (a recurring task's close, the blocked-close
  confirmation), which a direct write would skip.
- **Not verified, by design:** that a control exists for every published action
  (`restore_task` has no tap of its own — the undo lives in the snackbar, and
  `TaskDetailOverflowActionsTest` asserts only that the offer *appears*: whether tapping
  Undo restores the task is open defect #2723, so that test must not be read as covering
  the restore); that no control offers an action in the `loading` and `error` states the
  adapter refuses actions in; and any row or section the snapshot does not publish. The
  snapshot is an allowlisted projection, not an inventory of the screen.

So: passing the headless suite is not evidence that a control is wired or that anything
is rendered. The screen-behavior tests in it are evidence about the adapter and the
coordinator only; the host opt-in test separately pins registration, and neither kind
says anything about a Compose control.
