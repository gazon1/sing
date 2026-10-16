---
title: "Gate registry wave (б): GRADLE_TASK_PARITY, kover floor, and Part A design complete"
date: 2026-10-16
status: accepted
tags: ["ci", "gates", "gradle", "kover", "registry"]
---

## Context

Wave (а) of the gate-registry strangler fig moved the positive-control data from
`check-gate-wiring.py` tables into `_registry.py`. Wave (б) was scoped to three
Gradle-related parts:

- **Part I** — `GRADLE_TASK_PARITY`: a `dict[str, tuple[str, str]]` of declared
  CI/local asymmetries for individual Gradle tasks.
- **Part C** — `kover_verify_rules_without_usable_floor()`: file-content analysis
  that reads `kover { … verify { … bound { minValue } } }` blocks and checks
  bounds against `_KOVER_MIN_FLOOR`.
- **Part A** — `configured_gradle_check_tasks()`: file-content analysis that
  finds `detekt { }` blocks in build files.

## Decision

**Parts I and C are migrated.** Part A requires a separate design decision.

### Part I: `GRADLE_TASK_PARITY` → registry

The `GRADLE_TASK_PARITY` dict is pure data: a task name maps to a `(scope, why)`
pair, where `scope` is one of `ci`, `local`, `covered`, `both`. This is
structurally identical to the existing `Parity` type in `controls.py`, but
`GradleTaskParity.scope` must also express `covered` (a task CI reaches without
spelling it, e.g. via `koverReport`).

A new `GradleTaskParity` dataclass was added to `controls.py` with an expanded
`scope` literal. The dict was moved to `_registry.py` as `GRADLE_TASK_PARITY:
dict[str, GradleTaskParity]`. `check-gate-wiring.py`'s `check_gradle_task_parity()`
now reads the populated module-level variable instead of its own copy.

**Why `covered` needs a new type:** The existing `Parity.scope` is
`Literal["ci", "local", "both"]` and is used for gate-level asymmetries.
`GradleTaskParity` adds `covered` which has no meaning for gates — a gate is
either run or not; a Gradle task can be "covered" (CI reaches it without naming
it). The two types are kept separate to avoid conflating gate parity with
task parity.

### Part C: `_KOVER_MIN_FLOOR` → registry

The constant `_KOVER_MIN_FLOOR = 60` was a hard-coded integer in
`check-gate-wiring.py`. It was moved to `_registry.py` as a module-level
constant. `kover_verify_rules_without_usable_floor()` now uses the
`KOVER_MIN_FLOOR` variable populated by `_populate_derived_tables()`.

The function itself (brace-balanced block parsing, constant resolution, bound
checking) remains in `check-gate-wiring.py` — it is the applier, not the data.
The floor value is the data.

### Part A: deferred

`configured_gradle_check_tasks()` is a discovery function: it scans
`*/build.gradle.kts` for `detekt { }` blocks and returns a map of
`module:task -> build_file`. This is fundamentally different from Parts I and C:

- Parts I and C compare **declared** state (a table entry, a constant) against
  **derived** state (what the surfaces name, what the build files contain).
- Part A discovers which tasks **exist** in the build and checks they are
  **named** by a gate.

The asymmetry is: the registry **cannot** be the source of truth for "which
modules have `detekt { }`" without either (a) duplicating every module's build
file declaration into the registry, or (b) computing it at registry load time
using file reads — which would put file I/O in a module that is on the import
path of every gate.

This is the same constraint that motivated the original ADR: the registry holds
data, not code, not I/O. Expressing "this module has a detekt block" in the
registry requires a new design (likely: a registry field that is populated by
the same file-reading code that reads it, with a clear phase separation).

## Consequences

- `GRADLE_TASK_PARITY` and `_KOVER_MIN_FLOOR` live in `_registry.py`.
- `controls.py` exports `GradleTaskParity` alongside `Control`, `Edit`, `Parity`.
- `check-gate-wiring.py` sources both values via `_populate_derived_tables()`.

## Part A: design decision

`configured_gradle_check_tasks()` is discovery: it scans `*/build.gradle.kts` for
`detekt { }` blocks. It is not "pure data" in the same sense as `GRADLE_TASK_PARITY`
— the result is derived from file content rather than being a hand-written table.
However, the constraint is specifically about `controls.py` (no `subprocess`/`os`/`git`
imports because it is on the import path of every gate). The file reading already
lives in `check-gate-wiring.py`'s `_populate_derived_tables()`, which runs once at
module load.

**Design:**

1. `configured_gradle_check_tasks()` moves from a standalone function to being
   called inside `_populate_derived_tables()`.
2. Result stored in module-level `CONFIGURED_DETEKT_TASKS: dict[str, str]`
   (task name → build file path).
3. Registry adds `DETEKT_TASK_EXEMPTIONS: frozenset[str]` — modules that have
   `detekt { }` but are **not** expected to be wired to any gate, with a reason
   in a comment. The exemption set is the data; the discovery is the applier.
4. `check_wiring()` reads `CONFIGURED_DETEKT_TASKS` and `DETEKT_TASK_EXEMPTIONS`
   from the populated namespace.

This keeps the registry as data (the exemptions list) and the applier as code
(the discovery function). The asymmetry that makes Part A different from Parts I/C
is: Parts I/C compare declared state against derived state; Part A discovers
state and checks it is wired. The exemption set bridges the two: discovery stays
in the applier, but the "why is this module exempt" lives in the registry.
