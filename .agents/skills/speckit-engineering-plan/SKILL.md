---
name: speckit-engineering-plan
description: 'Choose the smallest sufficient solution and break it into work: design.md and tasks.md, each task bound to an AC-NN and a named test id. Sixth step of Engineering SDD, after the review-spec gate; plans only and writes no code.'
disable-model-invocation: true
---

# Engineering SDD — Plan

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug`, `depth` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `spec.md`, `model.md`, `verification.md` in the change directory.
- Write: `openspec/changes/<slug>/design.md`, `openspec/changes/<slug>/tasks.md`

## Goal

Choose the **smallest solution that satisfies the approved spec**, and break it into tasks someone
else can execute without this conversation.

### 1. Write `design.md`

```
# <title>

## Summary
## Current architecture and affected modules
## Proposed solution
## Alternatives considered
## Why this solution is the simplest
## Component and dependency changes
## State-model changes                (when applicable)
## Persistence and compatibility impact
## Testing strategy
## Rollout or migration               (when applicable)
## Risks and mitigations
## Task dependencies
## Non-goals
## Validation commands
```

The load-bearing sections:

- **Alternatives considered** — at least one real alternative, with why it lost. A plan that only
  describes what it chose has not made a decision, it has made a habit.
- **Why this solution is the simplest** — the actual argument, not "it is simple".
- **Component and dependency changes** — for every new interface, layer, framework component or
  pattern, name the **specific defect it removes**. If you cannot name one, it does not go in.
  A local change is preferred over an architectural one.
- **Validation commands** — the real commands, copy-pasteable.

Respect the constraints `investigation.md` found: Koin 4.x DSL, no `factory { }` for ViewModels,
injected `AutoCloseableCoroutineScope` rather than `viewModelScope`, no `stateIn`, no `runBlocking`,
Room changes need `SCHEMA_VERSION` + `AutoMigration`, expect/actual only across `platformModule()`.

### 2. Write `tasks.md`

```markdown
- [ ] T001 [P] <action, and the result that will exist afterwards>
  - Files: `path/to/file.kt`
  - Depends on: none
  - Verification: `<TestClass>::<testFunction>` — `./gradlew :shared:jvmTest --tests '<TestClass>'`
  - Acceptance: AC-01
```

Rules:

- **One concrete result per task.** "Update the sync layer" is not a task.
- **`[P]`** only for genuinely independent work. Two tasks marked `[P]` must not touch the same
  file — two agents writing one file is how a plan loses work silently.
- **Verification names a test id** from `spec.md`, not a vague command. Every task references an
  `AC-NN`; a task no criterion needs is scope you invented.
- Test and implementation tasks may be `[P]` relative to each other only when the contract is
  stable and the files are disjoint.
- Do not create a "write tests" task without naming the scenarios from `verification.md`.
- Do not merge unrelated changes to shorten the list, and do not split one coherent change into
  meaningless micro-steps.
- Group by **technical phase** (foundation → core → integration), not by user story. Nothing here
  is user-visible.

Ordering: the `review-plan` gate follows, and nothing is implemented until it is approved.

## Forbidden

- Proposing a new abstraction because it is theoretically general. Name the defect.
- Changing `spec.md`, `model.md` or `verification.md`. If the plan reveals the spec was wrong, stop
  and report it — do not quietly rewrite the requirement you were given.
- A task without a verification method or without an `AC-NN`.
- Marking tasks `[P]` because the order is inconvenient.
- Editing any code. This step plans; `implement` writes code, after the gate.

## Done When

- `design.md` has every required section, at least one considered alternative, and copy-pasteable
  validation commands.
- `tasks.md` covers every `AC-NN`, and every task names files, dependencies, a verification test id
  and an acceptance criterion.
- No two `[P]` tasks share a file.
- No file outside `openspec/changes/<slug>/` has been modified.