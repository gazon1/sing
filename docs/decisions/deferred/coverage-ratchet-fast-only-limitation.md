---
title: "Coverage Ratchet Fast Only Limitation"
date: 2000-01-01
status: DEFERRED
tags: ["deferred"]
---

**Found in:** 2026-10-05, coverage ratchet analysis of `feature/ai` after adding `AdrStorage` test.

**Backlog:** `docs/decisions/deferred-backlog.md#162`
**Tracked as:** #162
**Status: deferred**

**Full record:** `docs/decisions/2026-10-10-coverage-ratchet-fast-only-limitation.md`

`just cr` runs `fast`-only, so code whose only honest test is `slow` is invisible to
every coverage floor. The `slow` tag means "crosses a process boundary". The workaround
(class-splitting) was applied for `AdrStorage`; a general fix requires either measuring
two separate runs or a formal exemption mechanism.

**Revisit when:** A module's honest test is `slow` and class-splitting is not feasible.

---
