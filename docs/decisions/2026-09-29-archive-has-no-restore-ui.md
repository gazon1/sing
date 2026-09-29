---
title: "Archiving is a one-way door — no restore UI exists"
date: 2026-09-29
tags: [ui, tasks, gap]
---

## Context

`Maestro/flows/smoke/11-archive-restore-smoke.yaml` was written to cover
archive → restore. Archive works: the task leaves every list and appears in the
Archive screen. Restore does not exist.

`TaskDetailIntent.Domain.Restore` is declared in the intent model, but no screen
dispatches it. `ArchiveScreen` offers one action — "Archive completed tasks (tap
to archive all)" — and its rows navigate to the task detail, whose overflow menu
shows the same two items as any task: `Архивировать` and `Удалить`. There is no
`Восстановить` string anywhere in the codebase.

So an archived task can be viewed but not brought back through the UI.

## Idea

1. Add restore: a `Восстановить` item in the task detail menu when the task is
   archived, or a swipe action on the Archive row.
2. Leave it, and scope the flow to what exists.

## Decision

We did (2) for now, and recorded (1) as a real gap. The flow asserts archive →
visible in Archive, and its comment says a restore step belongs there once the UI
lands.

`TaskDetailIntent.Domain.Restore` is already modelled, so (1) is a UI wiring job
plus a menu condition, not a domain change.

## Consequences

- `Maestro/flows/smoke/11-archive-restore-smoke.yaml` is named for behaviour it
  does not test. When restore ships, the flow gains the restore step; do not
  rename the file before then, or the rename will hide that the flow is partial.
- Archiving remains destructive from the user's point of view. Until restore
  exists, a smoke flow that archives must not assume the data comes back.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/ArchiveScreen.kt`
- `Maestro/flows/smoke/11-archive-restore-smoke.yaml`
- Related: `2026-09-29-maestro-archive-seed-strategy.md`
