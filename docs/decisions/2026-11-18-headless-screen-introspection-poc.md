---
title: "Headless screen-introspection contract (POC) on Task Detail"
date: 2026-11-18
tags: [introspection, agent, task-detail, poc]
status: accepted
---

## Context

Issue #3035 asks for a bounded proof-of-concept of a headless, agent-readable
"screen introspection" contract on the Task Detail screen (inspired by Shopify Reflex):
an agent must be able to read the screen state and drive named actions without a
Compose UI, an emulator, or pixel scraping.

Baseline audited by the issue: `8f91713c63bac6a62bd6e7f2367337b850ff7cbf`.

The note was revisited when the review of PR #3036 was closed (issue #3039): the
input validation was split into a schema check that runs before the intent is built,
the `focus` object was pinned to the shape `describe()` actually emits, the dispatch
semantics were written down and tested, the size and the verification results were
measured, and the verdict now states explicitly whether the contract may be extracted
into a reusable one.

## Idea

Expose a thin adapter around the existing `TaskDetailCoordinator` that converts
(a) the coordinator's UI state into an allow-listed JSON snapshot and (b) named,
schema-described actions into `TaskDetailIntent.Domain` values. The adapter lives
on the test side of the codebase, so the POC proves the contract shape without
shipping a production surface.

## Decision

1. **Adapter in `shared/src/commonTest`**, package
   `com.singularity.todo.feature.tasks.introspection`
   (`TaskDetailScreenContract.kt`). No production code, no new Gradle module,
   no CLI, no MCP surface.
2. **State as `kotlinx.serialization.json.JsonObject`** built with `buildJsonObject`
   — no new dependency (kotlinx-serialization is already on the commonMain classpath).
3. **Allow-listed snapshot.** `snapshot()` emits only: `screen`, `status`, `message`
   and a `task` object with `id`, `title`, `priority`, `kind`, `completed`, `pinned`,
   `archived`, `project`, `tags`, `checklist`, `subtasks`, `reminders`.
   No domain graph dump, no credentials, no profile or user-content leakage.
4. **Explicit action allow-list** of 5 actions, each with a JSON-Schema-ish input
   description and a mapping to exactly one existing intent:
   `set_priority`, `add_checklist_item`, `toggle_complete`, `delete_task`, `restore_task`.
   The coordinator's ~40 remaining intents are intentionally not exposed.
   The canonical field names are the intent's own: `set_priority` takes
   `{priority}`, `add_checklist_item` takes `{title}`. Every published schema states
   `additionalProperties: false`, because the dispatcher refuses a property the
   schema does not define and JSON Schema allows additional properties by default.
5. **Validation before dispatch, in two steps.** `dispatch(action, input)` first looks the
   action up by name, then checks that the screen can act on it right now, then checks
   the input against the action's published schema, and only then maps the input to
   one `TaskDetailIntent.Domain` value and calls `coordinator.onIntent(...)`. The
   schema check (an unknown property) is a separate step that runs *before* the
   mapping, so an input that does not fit the schema never reaches the intent; the
   mapping itself refuses a required field that is missing, of the wrong type, or
   outside the accepted set. On failure the call returns `Result.failure` with a
   message naming the action, the reason, the expected input schema and the list of
   supported actions. The input has to match the published schema exactly: a
   property the schema does not define is refused, not ignored, so an agent cannot
   believe a flag it invented took effect. An action whose schema takes no input
   therefore takes no property at all. The published schema says the same
   (`additionalProperties: false`), so a reader who validates against it is never
   told "valid" for input the screen refuses.
6. **Availability is part of the contract.** `describe()` publishes every action with
   an `available` flag and the requirement behind it, and `dispatch()` refuses an
   unavailable action instead of reporting a successful no-op. Every action needs the
   screen to be showing a task: while the screen is loading or has failed to load, the
   coordinator's slots have no task to work on, so an action dispatched then would
   report success while changing nothing. `restore_task` needs more — this screen has
   to hold an undo: the contract performed a delete that was not undone, and the screen
   still shows the task as deleted.
7. **The availability rule is a function of the screen state.** `Action.isAvailable(state)`
   takes the state it is asked about, and the contract exposes `describe(state)` and
   `dispatch(state, ...)` alongside the live-screen versions. That is what lets the
   loading and failed states be asserted without a coordinator that refuses to settle —
   and it keeps the rule in one place instead of two.
8. **No reflection** over the sealed intent hierarchy: each action maps to an
   explicit `TaskDetailIntent.Domain` constructor call.
9. **Undo reuses the existing intent path** — `delete_task` → `TaskDetailIntent.Domain.Delete`,
   `restore_task` → `TaskDetailIntent.Domain.Restore` (the same intent the snackbar's
   "Undo" button sends, see `TaskDetailContent.kt`).
10. **Real coordinator, real fakes.** The contract wraps the actual
   `TaskDetailCoordinator` with the existing `SlotFakes`; the test asserts the JSON
   snapshot *and* the fake repository state, proving the screen path produced the result.
11. **The undo follows the task, not the attempt.** Whether the undo is on offer is
    decided by the task still being deleted, not by the fact that a restore was tried:
    `TaskLifecycleSlot.restore()` keeps its snapshot when the write fails — the task
    would be lost if the snapshot went with the failure — so the screen can offer the
    undo again, and the contract has to offer the same retry. A restore that succeeds
    takes the task out of the deleted state, and the undo is then spent.
12. **The focused node is published as `focus`, with exactly two fields.**
    `describe()` emits `focus: {taskId, title}` while the screen is showing a task,
    and no `focus` at all in any other state. `taskId` is the task on the screen;
    `title` is the title the screen currently shows — the title draft, so an edit in
    progress is what a reader sees. This note and the emitted JSON say the same
    thing, and a test asserts the shape so the two cannot drift apart.

## Rationale

The issue is a proof of the *contract*, not of a shipping feature. Keeping the adapter
in `commonTest` means: no production surface to maintain, no new module, no CI cost,
and the whole thing runs on `:shared:jvmTest` without Compose, emulator, or wall-clock
sleeps. An allow-list (rather than mirroring every intent) is what makes the surface
safe for an agent: unknown or not-yet-exposed intents are refused, not silently ignored.

## Dispatch semantics

`dispatch()` returning `success` means exactly one thing: **the action was accepted
and handed to the coordinator.** It does not mean the operation the action started
has completed, and it does not mean the write has landed.

The screen does that work asynchronously — the slots launch it — so the sequence an
agent sees is:

1. `dispatch(action, input)` → `success`, the intent has been handed to
   `TaskDetailCoordinator.onIntent`.
2. The screen does the work; the repository is written; the screen's state is
   reassembled.
3. `snapshot()` shows the outcome.

A failure of the underlying write is reported the way the screen reports it — as a
`TaskDetailUiEvent.Error` — and not as a failed dispatch. So the contract answers two
different questions: "was the action accepted?" (the dispatch result) and "did it
work?" (the next snapshot). A caller that needs the second has to look; the POC does
not pretend the first implies the second.

## Verification

Actual commands and results (2026-11-18, Linux, warm Gradle cache):

```bash
./gw :shared:jvmTest --tests "com.singularity.todo.feature.tasks.introspection.*"
# BUILD SUCCESSFUL — 13 tests, 0 failures, 0 errors, class wall clock 1.02 s

./gw :shared:detekt          # BUILD SUCCESSFUL, exit 0
python3 scripts/ktlint-lint.py shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/introspection/*.kt
# nothing to report
easement check               # 2 violations, both pre-existing and unrelated
#   (desktopApp -> shared -> desktopApp cycle; shared/src/jvmTest importing desktopApp)
./gw :shared:jvmTest          # full shared lane — 5007 tests, 0 failures
```

Runtime: 13 tests in 1.02 s; a cold `:shared:jvmTest` run is ~3 min, a warm filtered run ~30 s.

### Measured size

- `TaskDetailScreenContract.kt` — 413 lines (the adapter).
- `TaskDetailScreenContractTest.kt` — 543 lines (13 tests).
- This note — the decision record.

That is the whole POC: 956 lines of test code, no production code, no new Gradle
module, no new dependency, no Compose, no emulator.

The negative paths are asserted, not assumed: a screen that is loading or has failed,
an action that is not exposed, a malformed input, an input carrying a property the
schema does not define (for an action with an input and for one without), an undo
that cannot be taken, and an undo whose write failed — the last one proves that a
failed restore keeps the undo on offer, so the agent can retry the way the screen
does. Two tests pin the semantics this note documents: the shape of the `focus`
object, and the difference between an accepted dispatch and a completed operation.

## Consequences

- The contract is a **POC**, not an API: it lives in test sources, is not shipped,
  and is not reachable by any external driver yet.
- Exposing more actions later is additive — one `Action` entry + one intent mapping.
- If the POC graduates to production, the natural home is a `core/llm`-style port
  (like `core/llm/ToolCallSummary.kt`), not the test tree; the snapshot allow-list
  and the validation-before-dispatch rule must be preserved.
- The action set is deliberately small: an agent driving Task Detail today can
  prioritise, add checklist items, complete, delete and undo — nothing else.

## Limitations

- Task Detail only; no other screen is introspectable.
- 5 of ~40 coordinator intents are exposed.
- `describe()` publishes a static capability list plus a per-action `available` flag
  and the requirement behind it; there is no focus/selection model beyond the single
  `focus` object, which is published only while the screen shows a task and carries
  exactly `taskId` (the task on the screen) and `title` (the title the screen shows —
  its draft).
- The loading state is not reachable end-to-end with the current fakes — the fake
  repository always answers, so the screen settles into `loaded` or `error` — so the
  loading case is asserted through the same predicate the dispatch path uses.
- The screen keeps its undo snapshot in its own lifecycle slot, and that snapshot is
  deliberately not part of the assembled screen state — so the contract cannot read it
  and tracks the delete it performed itself. A delete made through the real UI is
  therefore not undoable through the contract, and the tracking is per contract
  instance: a fresh contract does not know about an earlier one's delete.
- A successful `dispatch` means the action was accepted and handed to the screen, not
  that the write it started has landed: see [Dispatch semantics](#dispatch-semantics).
  The outcome shows up in the next `snapshot()`.
- Runs on the JVM only (`:shared:jvmTest`); no Android instrumentation flow.
- No persistence/undo window of its own — undo is exactly the existing
  `Domain.Restore` intent.

## Verdict

**GO.** The contract shape is proven end-to-end without touching production code:
a headless agent can read an allow-listed JSON snapshot of Task Detail, drive five
named actions through the real coordinator, and get readable refusals for anything
malformed or not exposed.

### Extracting a reusable contract: authorized, with a boundary

**Authorized.** The contract *shape* this POC proves — screen identity, an
allow-listed snapshot, an action catalogue with JSON-Schema inputs, availability
flags, validate-before-dispatch, and the acceptance-vs-completion distinction — is
good enough to be extracted into a reusable contract rather than left as a
one-screen adapter. The next milestone may build that reusable layer on top of this
POC, and the POC's adapter is the reference implementation.

**Bounded.** What is authorized is the *shape*, not a production surface:

- The extraction stays out of production code until the architecture decision
  (#3046) picks the home (a `core/llm`-style port, a module, or a driver). This
  note does not pre-decide that.
- The rules the POC proved are not negotiable in the extraction: the explicit
  allow-list of actions, the `available` flag and its requirement, the snapshot
  allow-list, the exact-schema input check (unknown properties refused, not
  ignored), and the refusal messages that name the supported actions and the
  expected input shape.
- What the POC does *not* prove carries over as an open item, not as a hidden
  assumption: the undo tracking is per contract instance, the loading state is not
  reachable end-to-end with the current fakes, and dispatch success is not operation
  completion.

Smallest next step (not built here): a thin driver/CLI that runs this contract
outside the test tree, plus an agent skill that teaches the agent the action set and
the refusal semantics.

## Links

- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/introspection/TaskDetailScreenContract.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/introspection/TaskDetailScreenContractTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskDetailCoordinator.kt`
- Issue: https://github.com/gazon1/sing/issues/3035
- Issue: https://github.com/gazon1/sing/issues/3039 (review round closed by this note)
