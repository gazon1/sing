# AI benchmark for stale knowledge (Phase 1d)

Goal: verify agents detect intentionally stale concepts when using knowledge.

## Branches

- A — Baseline: agent without knowledge.
- B — Unmarked: agent reads knowledge but no status.
- C — Status-aware: agent uses `show`/`status`.

## Procedure

1. Pin model, commit, task, and initial conditions across all runs.
2. Use one stale-trap task (intentionally outdated concept) plus 2-3 architecture tasks.
3. Run each branch at least 5 times for the stale-trap task.
4. Record results via `scripts/documentation/benchmark.py`.
5. Aggregate: stale detection rate (C must be 100%), invariants missed, false claims, manual re-checks.

## Go/No-go

Branch C must detect stale state in all 5 repeats. If not, fix read path (AGENTS.md, status, show, skills, checker errors) before migration.
