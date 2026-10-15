# Implementation Plan: Bounded Gate Liveness Probing

**Branch**: `013-gate-probe-cost` | **Date**: 2026-10-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/013-gate-probe-cost/spec.md`

## Summary

Two ADR gates account for 79 % of the measured liveness cost (120.3 s of 151.7 s serial) because each spawns one `git` process per record — and for one of them, per *commit* in that record's history. Cost is `O(ADRs × commits)`, so it degrades silently as the corpus grows. Separately, the liveness loop has a per-probe budget of 300 s and **no aggregate budget**, giving a 3.8-hour worst case that nothing reports.

The work has four parts, in dependency order:

1. **Batch the git reads** in `check-adr-immutable.py` — replace per-record and per-commit spawns with a bounded number of queries (`git log` for the commit set, `git cat-file --batch` for blob contents).
2. **Repair `is_registry_function()`** — it matches command text (`cmd.split()[0]`) and, like the heavyweight filter, can no longer fire since every command became `sgate <subcommand>`. Three registry shell-function gates are therefore probed when the gate's own contract says they must be counted, not probed. This is spec 012 FR-001, second site, same file.
3. **Bound the liveness probe** with an aggregate wall-clock budget, and report every gate the budget prevented from being reached.
4. **Bound probe output capture**, so probes running concurrently cannot exhaust resident memory.

Part 1 is the actual bottleneck and is worth 120 of the 131 seconds saved. Parts 2–4 are the guardrails that stop the class from recurring and from becoming a memory incident.

## Technical Context

**Language/Version**: Python 3.14 (stdlib only — `subprocess`, `shlex`, `re`, `pathlib`). No new dependency.

**Primary Dependencies**: `git` 2.56.0 (verified: `cat-file --batch` reads N objects per spawn; `log -- docs/decisions/` returns 365 commits per spawn). `adr_corpus` (local, `scripts/src/sgate/adr_corpus.py`) for frontmatter parsing.

**Storage**: N/A — reads a git working tree and its object database.

**Testing**: `unittest` via `python3 -m unittest discover -s scripts/tests` (the suite the gates themselves gate on). Temporary git repositories for history-shape tests, so history-dependent behaviour is testable without a 365-commit fixture.

**Target Platform**: Linux and macOS developer machines; GitHub Actions runners. No JVM required — this code runs in the static job, which is deliberately JVM-free.

**Project Type**: CLI gates.

**Performance Goals**: immutability gate < 5 s and frontmatter drift < 5 s on the 601-record corpus; liveness gate < 60 s on the current tree; process-spawn count independent of commits-per-record.

**Constraints**: working-tree-versus-HEAD comparison preserved (the gate's stated purpose is catching uncommitted edits to closed decisions); rename following preserved; resident memory bounded; `ci.yml` and `check.sh` entry points unmodified; skip rules from spec 012 not weakened.

**Scale/Scope**: 601 ADRs, 365 commits touching `docs/decisions/`, 49 registry gates, 46 probeable. Corpus grows monotonically, so runtime is specified as a function of corpus size, not as a fixed constant.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Applicable | Assessment |
|---|---|---|
| I. KMP Architecture | No | No application code touched. |
| II. ADR Before Code | **Yes** | This changes how a gate reaches a verdict, not what the gate decides. It does not change any contract between layers. Recorded as a spec + plan under `specs/`, which is this repo's own precedent for gate-internal work (`2026-10-16-gate-registry-wave-b-strangler`). |
| III. Validate Early and Often | **Yes** | `singularity-adr validate` runs after ADR changes. This work adds no ADR, so the hook is a no-op; it still runs. |
| IV. Immutable Historical Record | **Yes, and load-bearing** | The gate being optimised *enforces* this principle. Any optimisation must not weaken it. Hence FR-003 (working-tree comparison preserved) and FR-004 (rename following preserved) are constitutional constraints here, not preferences. |
| V. CI Gates | Yes | No new gate; no gate removed. Verdicts unchanged in class. |
| VI–VIII | No | Not application code. |

**Verdict: PASS.** No violations. The two gates in this plan are themselves the enforcement mechanism for Principle IV, so the plan is judged by whether it preserves the properties that principle describes.

**Complexity Tracking**: empty — no violations to justify.

## Phase 0: Research

### Decision 1 — How to read many records' history in a bounded number of spawns

**Decision**: Two queries. One `git log --format=%H --name-only -- docs/decisions/` to obtain the commit set and the per-path commit lists, then one long-lived `git cat-file --batch` fed with `<rev>:<path>` to resolve blob contents. Roughly 2 spawns instead of `O(records × commits)`.

**Rationale**: Verified on this repository: `git log -- docs/decisions/` returns 365 commits in one spawn; `git cat-file --batch` resolves an arbitrary number of `<rev>:<path>` references in one spawn. This is the standard batch-read idiom and needs no new dependency.

**Alternatives considered**:
- `git log -p -- docs/decisions/` and parse patches — 1 spawn, but patch parsing must reconstruct full file state from partial diffs to read frontmatter, and a frontmatter block that never changed after an unrelated edit would need the earlier blob anyway. More parsing, same order of cost.
- `git cat-file --batch-command` with `--filter` — faster on large corpora, but its filtering semantics differ for blobs at historical revisions and it is harder to reason about when a frontmatter field must be read exactly as it was. Rejected as premature; the simple form meets SC-001 with margin.
- Retain per-record spawns and add a cache — rejects the class of defect rather than the instance; the cache adds a staleness failure mode and does not fix first-run cost.

### Decision 2 — Per-file history without per-file spawns

`immutable_since()` currently uses `git log --follow` per path. `--follow` is inherently per-path, so batching must reconstruct the equivalent from the single commit walk.

**Decision**: Derive each path's commit list from the single `git log` pass, and detect renames from that same pass so the reconstructed list is equivalent to `--follow`. Where a rename is detected, the previous path's commits are attributed to the current path.

**Rationale**: Satisfies FR-004 without reintroducing per-path spawns. Rename detection from `--name-status` in the same pass is sufficient because a decision rename is a contiguous move, not a reorganisation.

**Alternatives considered**:
- Keep `--follow` for paths whose rename count exceeds a threshold — a hybrid that is correct but has two code paths to keep consistent, and the threshold is unmeasurable in advance. Rejected.

### Decision 3 — Aggregate probe budget

**Decision**: Add an aggregate wall-clock budget to `check-gate-liveness.py`, defaulting to a value comfortably above the measured post-fix serial sum (~31 s) and below the current 280 s failure point. Gates not reached because the budget expired are reported individually under a distinct verdict, `unreached`.

**Rationale**: FR-006 requires that a gate the budget prevented from running is named. Reusing the existing `slow` verdict would conflate "ran and was slow" with "never ran" — exactly the verdict-merging that this gate's own history says produced a false red CI job.

**Alternatives considered**:
- Lower `PROBE_TIMEOUT` from 300 s — reduces the worst case linearly but does not bound it, and 300 s is load-bearing for genuinely slow healthy gates on cold CI runners. Insufficient alone; retained as a secondary lever.
- Probe in parallel — reduces wall-clock but multiplies resident memory, which is the failure recorded in spec 012 SC-004. **Deliberately deferred**, not rejected: with the two ADR gates fixed, the serial sum is ~31 s and parallelism is not needed to meet SC-003. Revisit only if measurement after Part 1 shows otherwise. This is the plan's most consequential scoping decision and is stated as such so it can be contested.

### Decision 4 — Bounded output capture

**Decision**: Cap per-probe captured output; on exceeding the cap, mark the capture truncated and keep the leading bytes (the traceback marker, when present, appears early).

**Rationale**: `capture_output=True` currently holds a gate's entire output in memory. Measured: `gate scripts' own unit tests` runs 210 s and is verbose. It is skipped today, but the skip is a runtime judgement, not a guarantee — the bound must hold if that judgement changes.

**Alternatives considered**:
- Discard output and re-run failures — doubles the cost of exactly the case that matters most. Rejected.
- Redirect to a temp file — equivalent memory profile to a cap for the common case, more filesystem churn. Rejected in favour of the cap, which is simpler to reason about and to test.

## Project Structure

### Documentation (this feature)

```text
specs/013-gate-probe-cost/
├── spec.md              # Requirements, measured baseline, root cause
├── plan.md              # This file
├── research.md          # Phase 0 — the four decisions above
├── data-model.md        # Phase 1 — verdicts, budgets, batch-query shape
├── quickstart.md        # Phase 1 — how to reproduce the baseline and verify the fix
└── contracts/
    └── verdict-vocabulary.md   # Probe verdict set, incl. the new `unreached`
```

### Source Code (repository root)

```text
scripts/
├── check-adr-immutable.py     # Part 1 — batch git reads; verdicts unchanged
├── check-gate-liveness.py     # Part 2 — aggregate budget + `unreached`; Part 3 — output cap
└── tests/
    ├── test_check_adr_immutable.py   # New — history shapes, renames, dirty tree
    └── test_check_gate_liveness.py   # New — budget exhaustion, unreachable naming

# Untouched by this work:
scripts/ci/static-gates.sh    # Registry: no gate added, removed, or reclassified
.github/workflows/ci.yml      # Entry point — not modified
check.sh                      # Entry point — not modified
```

**Structure Decision**: Both targets already exist as standalone gate scripts with unit-test files under `scripts/tests/`; the work extends them in place. No new module, no new package, no new entry point — matching the repo's existing gate layout.

## Complexity Tracking

> No constitution violations. One scoping decision is recorded here because it is the one most likely to be contested.

| Decision | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Parallel probing deferred, not adopted | FR-005 needs a *bound*, which a budget provides directly | Adopting parallelism now would multiply resident memory — the failure in spec 012 SC-004 — to solve a wall-clock problem that Part 1 already resolves. After Part 1 the serial sum is ~31 s, inside SC-003 without it. If measurement contradicts that, this is the first decision to revisit. |

## Recorded, Not Fixed Here

Two defects were found while investigating and belong to a different change. Naming them so they are not lost:

- **Dangling registry reference.** `GateSpec(name="decisions digest within budget")` declares `bash_func="digest_and_sizes"`; that identifier appears zero times in `static-gates.sh`. Both render paths (`gates.py:50`, `_registry.py:698`) build `bash -c '. static-gates.sh && digest_and_sizes'`, which is `command not found`.
- **Two paths to one gate that disagree.** Probing via `sgate <name>` resolves to the Click command and passes (rc=0, 0.401 s); the registry's `BASH_C` path points at the missing function. A probe exercises only the healthy path, so this cannot be observed from a probe result.

Neither is a probe-cost defect. Both change which gates execute, and need their own verification.

## Verification of the Plan

| Criterion | How it is checked |
|---|---|
| SC-001, SC-002 | Timed on the full 601-record corpus, `/usr/bin/time` |
| SC-003 | Timed end-to-end on the current tree |
| SC-004 | A test registers N hanging gates; runtime compared against budget + per-probe budget |
| SC-005 | A test builds 100 synthetic ADRs × 10 commits; spawn count asserted |
| SC-006 | Resident memory sampled across a full run |
| SC-007 | Every run's output inspected for unreached-gate naming |
| SC-008 | Seeded violations compared against pre-change verdicts — the gate must still catch what it caught |