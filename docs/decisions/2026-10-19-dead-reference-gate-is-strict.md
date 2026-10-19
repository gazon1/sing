---
title: "The dead-reference gate is strict, and the six drifting paths were real paths, not debt to baseline"
date: 2026-10-19
status: accepted
tags: ["tooling", "docs", "ci", "gates"]
---

## Context

`scripts/check-doc-dead-refs.py` scans documentation for two forms of
reference — a path inside backticks, and a dated ADR slug — and resolves them
from the **repository root**. When a path does not resolve from the root, the
script falls back to matching the **basename** anywhere in the tree and
reports the result as *drift*: a warning that never fails a build.

That leniency is measurable. The script reported **40 dead references, all 40
baselined**, and — under the fallback rule — a further set of references whose
paths no longer exist but whose basenames still do.

## Idea

Turn on the script's own `--strict` mode, and fix the paths it objects to
instead of baselining them.

## Decision

**`--strict` is on in the gate, and the six drifting paths were corrected.**

- `scripts/src/sgate/commands/_registry.py` — the `GateSpec` for the
  dead-reference gate now passes `script_args=("--strict",)`; the registry
  control's `argv` and the `SCRIPT_GATES` entry in
  `scripts/check-gate-wiring.py` were updated to match.
- Six references were corrected in place:
  - `desktopApp/main.kt` → `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`
    in four files (two of them are KDoc comments in `shared/src/jvmMain/.../feature/reminders/`
    and one in `shared/src/jvmTest/.../feature/reminders/`), and
  - `shared/src/jvmTest/.../RunVmTest.kt` → `shared/src/commonTest/.../RunVmTest.kt`.

Current state: **40 dead (40 baselined), 0 path drift**, exit code 0.

## Rationale

- **A drifted path is a wrong instruction.** The references in question point
  a reader — or an agent — at a file that does not exist at the stated
  location. The basename fallback exists to be forgiving about *where* a file
  was written from; it is not evidence that the reference is correct.
- **These six were not debt, they were typos.** `desktopApp/main.kt` never
  existed at that path; the file has always been at
  `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`. `RunVmTest.kt`
  lives in `commonTest`, not `jvmTest`. Baseline entries would have frozen
  the mistake in the baseline file forever, and the baseline is exactly the
  mechanism that makes the gate "pass" while the documentation is wrong.
- **Strict mode is a switch that was already written.** The script supports
  `--strict`; leaving it off meant the strictness was untested and unowned.
- **The dead-reference scan and the new link check now say different things.**
  The link check (`lychee`) resolves Markdown links relative to the file that
  contains them; this gate resolves backticked paths from the repository root.
  That difference is intentional — the two forms are different forms — and
  each gate's failure points at a different kind of defect.

## Consequences

- **The gate now fails on drift**, which means a future path change in
  documentation fails the build until the reference is updated. That is the
  intent.
- **Six documentation references are now correct** rather than merely
  resolvable.
- **The 40 baselined dead references are unchanged.** They are a separate
  question: the gate's baseline records them, and this change did not
  re-argue them. Baseline entries are for genuine, known-dead references;
  a *drifting* path is a live reference that has gone stale, and those were
  fixed instead of baselined.
- **New files must be staged** for the script to index them: it reads the
  tree through `git ls-files`. A reference to an untracked file reads as dead.

## Links

- `scripts/check-doc-dead-refs.py` — the scan
- `scripts/src/sgate/commands/_registry.py` — the `GateSpec` and its `--strict` argument
- `scripts/check-gate-wiring.py` — the `SCRIPT_GATES` entry
- `docs/decisions/2026-10-19-doc-links-gate-lychee.md` — the companion link check
