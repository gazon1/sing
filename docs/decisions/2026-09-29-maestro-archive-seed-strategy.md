---
title: "Archive seed strategy — session coupling in archive-restore flow"
date: 2026-09-29
tags: [maestro, testing]
---

## Context

`Maestro/flows/smoke/11-archive-restore-smoke.yaml` (archive → restore)
depends on `10-archive-task-smoke.yaml` (complete → archive) having run in the
same session without `clearState: true`. The restore flow asserts that a
`task_item_buy_milk` row already exists in the archive list — but it creates
none of the data itself.

This coupling means:
- Running `11-archive-restore-smoke` in isolation fails (no archived task).
- The smoke set must run `10-archive-task-smoke` before `11-archive-restore-smoke`
  in the same `maestro test` invocation, in order.
- If `10-archive-task-smoke` ever changes its fixture title (e.g. "Buy milk"
  → "Buy groceries"), `11-archive-restore-smoke` breaks without the author
  touching it.

## Idea

1. **Accept the coupling.** Document it; flows that depend on shared fixture must
   run in a known order. Run the smoke set as a single `maestro test` invocation
   so ordering is preserved. This is how existing `seed-task.yaml` works.
2. **Create an archive seeder helper** (`seed-archived-task.yaml`) that runs
   `seed-task.yaml` → complete → archive in one subflow. Makes the dependency
   explicit and self-contained.
3. **Invest in MCP/debug-seed.** Use the production MCP server or a hypothetical
   debug-seed endpoint to create fixture data without UI. Fast, deterministic, no
   coupling. Requires infrastructure investment.

## Decision

We do approach (2) — a self-contained `seed-archived-task.yaml` helper.
It replaces the implicit session coupling with an explicit subflow call. This
keeps flows independently verifiable while staying within UI-driven seeding
(no new infrastructure).

Approach (1) accumulates hidden coupling. Approach (3) is the right long-term
answer but requires a debug-seed API that does not exist yet.

## Consequences

- A new `Maestro/helpers/seed-archived-task.yaml` helper is created in PR-2
  (Phase 2, Tasks flows). It runs `launch-clean` → `seed-task.yaml` →
  complete → archive in one subflow, producing one archived `Buy milk` task.
- `11-archive-restore-smoke.yaml` is updated to `runFlow:
  ../../helpers/seed-archived-task.yaml` instead of relying on the prior smoke
  flow.
- The helper is tagged `helpers` (never run standalone).
- If a future debug-seed API is added (approach 3), both seeder helpers become
  candidates for replacement — but that is an infrastructure decision, not a
  Maestro-convention decision.

## Links

- `Maestro/flows/smoke/10-archive-task-smoke.yaml`
- `Maestro/flows/smoke/11-archive-restore-smoke.yaml`
- `Maestro/helpers/seed-task.yaml`
- Related: `2026-09-29-maestro-dialog-buttons-no-testtag.md`
