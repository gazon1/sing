---
title: "Coverage ratchet measures fast-only runs — slow-testable code is invisible to every floor"
date: 2026-10-10
status: deferred
issue: "#162"
---

**Date:** 2026-10-10
**Status:** deferred
**Issue:** #162

## Context

`just cr` runs `:shared:jvmTest :desktopApp:test koverXmlReport` with **no** `-Ptest.tags`.
Every coverage floor is therefore measured from a `fast`-only run.

The project's own rule is that `slow` means *crosses a process boundary* — a Compose
harness, a real file or database, the real clock. So a test that is honestly `slow`
contributes **nothing** to any floor, and production code whose only honest test needs
the filesystem or a real database is invisible to the measurement entirely.

Any future module whose only honest test crosses a process boundary will do the same
thing: a floor that cannot see the code it guards, and a drop that reads as missing
tests. The floor is a claim about coverage, and here it is a claim about a subset of
coverage that nobody wrote down.

## Observed

Turning `AdrStorage` into a testable class added three uncovered production lines to
`feature/ai`. The covering test has to create and delete directories, which is `slow`
by the project's own definition. The ratchet reported:

```
feature/ai: measured 405/1721 = 23.53%   (floor was 405/1718 = 23.57%)
```

The **numerator did not move**. The drop was a denominator with no test behind it —
and the test did exist, running, and correct.

## Options considered

1. **Measure `fast,slow` and record both.** Costs runtime, and floors move.
2. **Record the tag policy as part of the measurement.** A floor that cannot see `slow`
   code is qualified with the fact, rather than silently not covering it.
3. **Exempt affected scopes from the floor with a named reason.** The gap is visible
   in the baseline file rather than in a git blame three weeks later.
4. **Split affected classes along tag lines.** Workaround: isolate `slow` code into
   `fast`-testable halves. Applied once for `AdrStorage`; does not scale.

## Decision

**Deferred.** All viable fixes require either significant recipe restructuring (measuring
two separate runs) or a formal exemption mechanism for slow-testable code. The
workaround (class-splitting) is sufficient for immediate needs. The gap is real but
narrow.

Revisit when:
- A module's honest test is `slow` and class-splitting is not feasible.
- Or: the CI infrastructure can cheaply produce `fast+slow` combined measurement.

## Related

- `#146` — the ratchet wipes the evidence it is about to measure. Same recipe,
  different defect.
- `openspec/changes/local-gate-repair` — owns the recipe itself.
- `#458` — `--no-build-cache` in CI is orthogonal but related: both improve what
  the CI measurement actually captures.
