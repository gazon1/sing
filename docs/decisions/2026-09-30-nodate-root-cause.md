---
title: "NoDate bisect — the domain is sound; the break is above AgendaEvaluator"
date: 2026-09-30
tags: [agenda, testing, debugging, bisect]
status: superseded
superseded-by: 2026-09-30-nodate-fix
---

# NoDate root cause — partial [SUPERSEDED]

> **Superseded by** `2026-09-30-nodate-fix.md`. The bisect was completed and the
> root cause identified and fixed.

## Original content preserved for history

`RelativeBucket.NoDate` maps to `DateRange(1970-01-01, 1970-01-01)`, which read
as a sentinel that no task can match. That reading is wrong: `SelectorMatcher`
special-cases the bucket with a direct `task.dueDate == null` branch.

`AgendaNoDateRegressionTest` (5 tests, green) pins that the **domain is sound**.

The bisect steps 2–4 were deferred to `deferred-backlog.md#nodate-steps-2-4`.

## Links

- `2026-09-30-nodate-fix.md` — completed fix
- `deferred-backlog.md#nodate-steps-2-4`
