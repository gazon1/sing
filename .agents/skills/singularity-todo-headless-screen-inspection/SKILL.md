---
name: singularity-todo-headless-screen-inspection
description: Drive a real app screen headlessly to inspect state, discover actions, invoke one, and verify the result — for implementation and for PR review. Use when a change needs behavioural evidence about a screen without an emulator or a Compose render, when a task detail or agenda behaviour has to be checked from the CLI or the MCP tools, or when deciding whether a Compose/Maestro/Allure test is still required. Covers starting the opt-in local host, the `agent` CLI and `screen_*` tools, the journey file, the safety rules, and troubleshooting unavailable host / unsupported screen / invalid input / async completion.
---

# Headless screen inspection

A screen's **behaviour** can be driven without rendering it: the app's own
introspection adapter is asked what it offers, one named action is dispatched
through the real coordinator, and the resulting state is read back. That is what
this skill is for.

Read `docs/architecture/screen-inspector.md` for the transport contract, the error
model and each adapter's actions. This skill is the *procedure*: what you run, in
order, and what the result does and does not prove.

## The one thing to get right first

**Headless evidence is behavioural evidence, not visual or accessibility
evidence.** The driver reaches `TaskDetailCoordinator.onIntent`; it never touches a
Compose control. So a passing headless run says the adapter, the dispatcher and the
screen's intent path agree. It does **not** say a button exists, that a control is
wired to the action, that a value renders, or that a screen reader can reach it.

Keep the two claims apart when you report:

| Claim | Evidence |
|---|---|
| the action reaches the coordinator and the state changes | headless inspect → invoke → inspect |
| a Compose control produces that write | desktop UI test (`singularity-todo-desktop-compose-ui-tests`), tap the real control |
| the screen renders / scrolls / is accessible on a device | Maestro flow, Allure report |

Asking for the first kind does not retire the others. A behaviour change still owes
the test kind that measures what it changed.

## Scope: what is actually shipped

One screen is registered with the host: `tasks.task-detail`, with
`set_priority`, `add_checklist_item`, `toggle_complete`, `delete_task` and
`restore_task`. `agenda.tasks` exists as an adapter but is **not** reachable from
the host — it is exercised only by the cross-screen journeys in tests.

Ask the host, do not remember: `agent screens` is the inventory. An id it does not
list is not supported, and inventing one is how you get a convincing false negative.

## Start an explicitly enabled host

The host is off twice over: the **build** must compile it in
(`-Psingularity.inspector.host.enabled=true`) and the **process** must opt in.
Neither defaults on, and a release build passes neither — no argument turns it on
there. This is deliberate; do not look for a way around it.

```bash
# Build the fat JAR with the capability compiled in (development output only).
./gw -Psingularity.inspector.host.enabled=true :mcp-server:jar

# One-shot CLI command.
java -jar mcp-server/build/libs/mcp-server.jar agent screens

# Long-lived MCP host, for many interactive calls in one runtime.
java -jar mcp-server/build/libs/mcp-server.jar --inspector --profile-id=<id>
```

Through Gradle instead of the JAR:

```bash
./gw -Psingularity.inspector.host.enabled=true :mcp-server:run \
  --args="--inspector --profile-id=<id>"
```

Data isolation uses the same profile contract as the MCP server: `--profile-id=<id>`
operates on that profile, `--profile=<label>` keeps its legacy meaning (only
`ai-agent`/`agent` do anything). `.singularity-todo/` under `user.home` holds the
database, so `-Duser.home=<dir>` runs the whole thing against a throwaway copy
instead of the profile you demo from.

**Put the profile flag before the subcommand.** `agent --profile-id=<id> open …`
is accepted; `agent open --profile-id=<id> …` is not — the subcommand has no such
option and answers `invalid_arguments`. Same for `journey`.

The host builds its own Koin container and drives **its own** screen session. It is
not the Desktop window you have open: unsaved buffer state on the live window is
invisible to it. See ADR
`2026-11-21-the-host-inspects-a-real-graph-session-not-the-window.md`.

## Where a task id comes from

The screen needs a **real task id**, and a clean checkout has none. Use the MCP
server you are already connected to:

```text
list_tasks   →  one row's "id" is a taskId this screen can open
```

`list_tasks` already scopes to the right profile, so it is the only source you need.
The inspector and the MCP server read the same database, so a task `list_tasks`
returns is one the screen can open under the same profile.

If the host answers `status: "error"`, `message: "Not found"` for an id `list_tasks`
returned, the two calls ran under different profiles. Compare the profile flags
rather than reading the database. Only when you must resolve a profile id itself is
the active profile name needed, and it is one value:

```bash
grep -ao '01[A-Z0-9]\{24\}' ~/.singularity-todo/profile_state.preferences_pb | head -1
```

That prints the active profile id — no task rows, no titles, no account identifiers.
Pass it before the subcommand (`agent --profile-id=<id> open …`).

Do not query `tasks` for ids or titles. The snapshot and `list_tasks` are allowlisted
projections; the raw table is not, and a title is exactly the private text the safety
rules keep out of your context.

A clean checkout's `~/.singularity-todo/` is created on the first run and holds no
tasks. Create one in the Desktop app, or through the MCP tools, under the same
profile you pass to the host.

## The CLI: one process, one command

Every non-journey invocation is a fresh process with an in-memory runtime, so a
session or operation id from one command is dead in the next. stdout carries the
JSON result and nothing else — log lines go to stderr — so piping to `jq` works.

```bash
INSPECTOR="java -jar mcp-server/build/libs/mcp-server.jar agent"

# 1. Discover: which screens, and what parameters each wants.
$INSPECTOR screens | jq -r '.result.screens[].id'

# 2. Open a screen session and read the initial snapshot and action list.
$INSPECTOR open --screen tasks.task-detail --task-id <TASK_ID>   # or --params '{"taskId":"..."}'
```

`open` returns one JSON object with `sessionId`, `screen`, `params`,
`description`, `snapshot` and `actions`. It **waits for the screen to settle**, so
in practice `snapshot.status` is `loaded` or `error` by the time you read it — the
wait is what removed the always-`loading` race. It is a bounded wait (5 s), so a
screen that is still initialising at the deadline comes back as `loading`: treat that
as "ask again", not as a transport failure, and check the status before you invoke.
The snapshot is an **allowlisted projection**, not an inventory of the screen: a
field absent from it is not evidence that the screen does not show that field.

`open`'s own answer already carries the first `snapshot` and the full `actions` list
with schemas, so the discovery step is done in one command. What it does **not**
carry forward is the session: every non-journey command is its own process over its
own in-memory runtime, so the `sessionId` `open` printed is dead by the next command.
`describe`, `inspect`, `actions`, `invoke`, `wait` and `cancel` are one-shot probes
for a screen's shape **within a single invocation** — in a fresh process they answer
`invalid_arguments`, "No screen session is open". Anything that has to span steps
(inspect → invoke → verify) is a **journey** (below) or the MCP tools.

## Invoke an action and wait for its outcome

`invoke` returns `status: "accepted"` when the action was validated and handed to
the real screen path. That is **dispatch**, not outcome. Outcome is observed from
post-dispatch screen state, and `waitMs` on the journey step waits for it — inside
the one runtime that also holds the session:

```json
{ "name": "prioritize", "op": "invoke",
  "action": "set_priority", "input": { "priority": "high" }, "waitMs": 5000 }
```

```json
{"operationId":"op-…","action":"set_priority","status":"accepted",
 "wait":{"operationId":"op-…","action":"set_priority","status":"completed",
         "snapshot":{"status":"loaded","subject":{"priority":"high"}}}}
```

Read it like this:

- `status` is `accepted | completed | failed | timeout`. Only `completed` and
  `failed` are outcomes.
- `completed` means the action's own post-condition is satisfied in the observed
  state — not that some state was emitted.
- `timeout` is the **wait's** deadline, not the operation's fate. The operation is
  not terminalized; a later `wait` in the same runtime can still observe
  `completed`/`failed`. Do not report a timeout as a failure.
- A pending operation is failed by the session at its own ten-second observation
  deadline.
- `cancel` answers `unsupported` for a still-dispatched action: execution cannot be
  stopped once handed to the real path. It reports status for an already-terminal
  one.

## Verify: state after the change

Read the state back and compare it with what the action promised. The post-condition
is in the action's summary; the value to assert is in the snapshot. The read-back is
a `snapshot` step of the same journey — that is what keeps it in the session that
holds the write:

```json
{ "name": "verify", "op": "snapshot" }
```

A verification that does not name the field it checked and the value it expected is
not a verification. `"ok": true` on the invoke is not one.

## A journey: a stateful multi-step workflow

Journeys share one runtime and session across steps, which is the only way to do
inspect → invoke → inspect in one process. They are also the shape a repeatable
check takes.

```json
{
  "steps": [
    { "name": "open",      "op": "open",   "screen": "tasks.task-detail", "params": { "taskId": "<TASK_ID>" } },
    { "name": "inspect",   "op": "snapshot" },
    { "name": "prioritize","op": "invoke", "action": "set_priority", "input": { "priority": "urgent" }, "waitMs": 5000 },
    { "name": "verify",    "op": "snapshot" },
    { "name": "close",     "op": "close" }
  ]
}
```

```bash
$INSPECTOR journey --file journey.json
```

Steps: `open`, `snapshot` (alias `inspect`), `describe`, `actions`, `invoke`,
`wait`, `cancel`, `close`, `screens`, `sessions`. Results are per-step
`ok`/`durationMs`/`result`; the run stops at the first failure unless the step sets
`"continueOnError": true`.

Read the result at the right depth: an `invoke` step carries its outcome under
`.result.wait`, the `snapshot` steps under `.result`:

```bash
$INSPECTOR --profile-id=<id> journey --file journey.json \
  | jq '{ok, before: .result.steps[1].result.subject.priority,
             outcome: .result.steps[2].result.wait.status,
             after: .result.steps[3].result.subject.priority}'
# {"ok":true,"before":"low","outcome":"completed","after":"urgent"}
```

That is the whole loop: `open` settles, `snapshot` reads the value before, `invoke`
completes, `snapshot` reads it after. Set the field to something *different* from
its current value first, and assert `before != after` with `after` equal to the
input — an `ok: true` whose `before` equals `after` is a no-op that proves nothing
about the write.

The `verify` step is worth keeping even when you are sure: it is what turns "the
invoke returned" into "the state moved".

## Driving the same runtime interactively (MCP tools)

When you need many calls against one live session — exploring, or correcting a
mistake mid-flight — start the long-lived host with `--inspector` and use its
tools instead of spawning a process per command:

`screen_list`, `screen_sessions`, `screen_open`, `screen_describe`,
`screen_snapshot`, `screen_actions`, `screen_invoke`, `screen_wait`,
`screen_cancel`, `screen_close`.

They speak the same JSON shapes and the same error codes as the CLI. The tools are
registered only under the opt-in; a host started without `--inspector` registers no
`screen_*` tool at all.

## Errors are structural and deterministic

A rejected call never dispatched anything. Read the `error.code`:

| code | meaning |
|---|---|
| `unknown_screen` | the id is not in the host's inventory (`screens`) |
| `session_not_found` | the session id is dead — a new process, or a `close` |
| `operation_not_found` | the operation id is dead in this runtime |
| `unknown_action` | the name is not one this screen publishes (`actions`) |
| `action_not_available` | published, but refused in the current state |
| `invalid_action_input` | fails the action's schema, e.g. a bad enum value |
| `invalid_arguments` | a malformed command or journey step — including no session open |
| `unsupported` | e.g. cancelling a dispatched action |
| `operation_timeout` / `operation_failed` | the operation itself |
| `internal` | unexpected; read stderr |

A task that does not exist is **not** an error code: `open` succeeds and the
snapshot says `status: "error"`, `message: "Not found"`. Check the snapshot status
before you invoke anything, or you will get `action_not_available` and chase it.

## During implementation, and during review

**Implementing** — after a behavioural change, drive the affected action and assert
the state moved. This is cheaper than a device run and it tells you *now* whether
the adapter, the dispatcher and the intent path still agree. It does not replace
the test the change owes; it shortens the loop before you write it.

**Reviewing a PR** — when a diff claims a behaviour changed, the headless run is
how you check the claim instead of reading it. Two rules:

- Compare the *published* surface (`actions`, its schemas, `available`) against the
  diff. An action advertised in a state where the screen's own handler does nothing
  is a defect: availability has to describe what that action does in the current
  mode.
- A passing run is not a review verdict. It is evidence about the adapter and the
  coordinator only. Wiring, rendering and accessibility still need their own
  evidence, so say which claim you actually checked.

## Safety

- **Secrets and private data.** Snapshots are allowlisted projections, but a run
  can still surface personal task text. Never paste a snapshot into a PR, an issue
  or a commit; quote the field and value you asserted, nothing more.
- **Never build a production endpoint.** The host is development/test only. Do not
  add a flag, a `-P` passthrough in a release path, or a network listener to make it
  reachable — the release guard exists so a package cannot carry the host.
- **Never infer.** An action the `actions` call did not publish, or a screen
  `screens` did not list, is unsupported. Do not construct ids or inputs from
  reading the code and report the failure as a bug in the host.
- One profile per investigation. Pass `--profile-id` so an experiment cannot write
  into the profile you demo from.

## Troubleshooting

**`unsupported`: the host is not compiled in.** `"The screen inspector host is not
compiled into this build."` — the JAR or distribution was built without
`-Psingularity.inspector.host.enabled=true`. Rebuild with the property; a release
artefact cannot be made to host.

**No `screen_*` tool at all.** The build has the capability but the process was
started without the opt-in flag. Add `--inspector`. For the CLI there is no flag —
entering an `agent …` command is the opt-in; `unsupported` there means the build.

**`unknown_screen`.** The id is not registered. Run `screens`; do not retry a
variant of the id. `agenda.tasks` is one that exists in tests and not in the host.

**`invalid_action_input`.** Read the action's `input` schema in `actions`: the
enum values, `required`, and `additionalProperties: false` mean the object must
have exactly those keys. A blank `taskId` is rejected by both the schema
(`pattern: "\\S"`) and the dispatcher — same refusal, one message.

**`session_not_found` on a command that just opened a session.** You are in the
next process. Each non-journey command is its own runtime; use `journey` or the MCP
tools for a sequence. A bare `inspect`/`wait` with nothing open answers
`invalid_arguments` — "No screen session is open" — not `session_not_found`; that
one is for an id that was open somewhere else.

**`status: "error"`, `message: "Not found"`.** The task id does not exist in the
profile you passed. A clean checkout has an empty database, and a task created under
one profile is invisible under another. Get an id from the app's own database
(section above) and match the profile flag to it.

**`invalid_arguments` on a command whose options look right.** The profile flag is
in the wrong place — it belongs before the subcommand (`agent --profile-id=<id>
open …`), not after it.

**`accepted` and nothing else.** You did not wait. There is no CLI command that
opens and invokes in one call, so the fix is a journey step with `waitMs`, or
`wait --operation <id>` as the next step of the same journey — a bare `wait` in a
fresh process has no operation to find. A timeout is not a failure — wait again.

**`action_not_available` while the screen looks loaded.** Availability is per
action and per mode. The list screen refuses row actions in multi-selection mode;
the detail screen refuses everything while `loading` or `error`. `describe` the
state before concluding the screen is broken.

**`failed` with an error message.** The message comes from that action's own screen
path (for example `Delete failed`). It is the application's failure, not the
transport's — read it as you would a UI error.
