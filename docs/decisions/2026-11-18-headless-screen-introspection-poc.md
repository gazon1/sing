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
5. **Validation before dispatch.** `dispatch(action, input)` validates the action name
   and the input shape *before* calling `coordinator.onIntent(...)`; on failure it
   returns `Result.failure` with a message naming the action, the accepted values,
   the expected input schema and the list of supported actions. The input has to
   match the published schema exactly: a property the schema does not define is
   refused, not ignored, so an agent cannot believe a flag it invented took effect.
   An action whose schema takes no input therefore takes no property at all. The
   published schema says the same (`additionalProperties: false`), so a reader who
   validates against it is never told "valid" for input the screen refuses.
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

## Rationale

The issue is a proof of the *contract*, not of a shipping feature. Keeping the adapter
in `commonTest` means: no production surface to maintain, no new module, no CI cost,
and the whole thing runs on `:shared:jvmTest` without Compose, emulator, or wall-clock
sleeps. An allow-list (rather than mirroring every intent) is what makes the surface
safe for an agent: unknown or not-yet-exposed intents are refused, not silently ignored.

## Verification

Actual commands and results (2026-11-18, Linux, warm Gradle cache):

```bash
./gw :shared:jvmTest --tests "com.singularity.todo.feature.tasks.introspection.*"
# BUILD SUCCESSFUL — 11 tests, 0 failures, 0 errors, class wall clock 1.13 s

./gw :shared:detekt          # BUILD SUCCESSFUL, exit 0
python3 scripts/ktlint-lint.py --require shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/introspection/*.kt
# nothing to report
easement check               # easement passed: 0 violation(s), exit 0
./gw :shared:jvmTest          # full shared lane — 5005 tests, 0 failures
```

Runtime: 11 tests in 1.13 s; a cold `:shared:jvmTest` run is ~3 min, a warm filtered run ~50 s.

The negative paths are asserted, not assumed: a screen that is loading or has failed,
an action that is not exposed, a malformed input, an input carrying a property the
schema does not define, an undo that cannot be taken, and an undo whose write failed —
the last one proves that a failed restore keeps the undo on offer, so the agent can
retry the way the screen does.

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
  `focus` object, which carries the focused task's `taskId` and `title`.
- The loading state is not reachable end-to-end with the current fakes — the fake
  repository always answers, so the screen settles into `loaded` or `error` — so the
  loading case is asserted through the same predicate the dispatch path uses.
- The screen keeps its undo snapshot in its own lifecycle slot, and that snapshot is
  deliberately not part of the assembled screen state — so the contract cannot read it
  and tracks the delete it performed itself. A delete made through the real UI is
  therefore not undoable through the contract, and the tracking is per contract
  instance: a fresh contract does not know about an earlier one's delete.
- A successful `dispatch` means the action was accepted and handed to the screen, not
  that the write it started has landed: the outcome shows up in the next `snapshot()`.
- Runs on the JVM only (`:shared:jvmTest`); no Android instrumentation flow.
- No persistence/undo window of its own — undo is exactly the existing
  `Domain.Restore` intent.

## Verdict

**GO.** The contract shape is proven end-to-end without touching production code:
a headless agent can read an allow-listed JSON snapshot of Task Detail, drive five
named actions through the real coordinator, and get readable refusals for anything
malformed or not exposed.

Smallest next step (not built here): a thin driver/CLI that runs this contract
outside the test tree, plus an agent skill that teaches the agent the action set and
the refusal semantics.

## Links

- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/introspection/TaskDetailScreenContract.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/introspection/TaskDetailScreenContractTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskDetailCoordinator.kt`
- Issue: https://github.com/gazon1/sing/issues/3035
