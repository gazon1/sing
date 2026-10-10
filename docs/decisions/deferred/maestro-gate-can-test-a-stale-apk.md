---
title: "Maestro Gate Can Test A Stale Apk"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** [#84](https://github.com/gazon1/sing/issues/84)

**Found in:** MR-6, chasing journey 07's empty profile picker.

The emulator died mid-run; `run-maestro.sh` relaunched it from an AVD snapshot
and, with `SKIP_INSTALL=1`, did not reinstall. The snapshot held an older build.
Every flow that "passed" in that run passed against a binary that predated the
branch's own hotfixes — including the fix for the very bug journey 07 was
reporting.

Verified by dex rather than by inference: the installed APK's `SingularityApp`
class had zero references to `ProfileBootstrapper`; the freshly built one has it.

**Fix (process):** after any device recovery, re-install before trusting a
result. Stated in `docs/plans/2026-10-04-mr6-retro-gate.md` §6.

**Fix (harness, not done):** have `run-maestro.sh` record the installed APK's
size and mtime at the start, compare after a recovery, and fail loudly if they
differ — or simply drop `SKIP_INSTALL=1` on the recovery path. The flag exists
to save time, and it costs correctness exactly when the run is already going
wrong.

---
