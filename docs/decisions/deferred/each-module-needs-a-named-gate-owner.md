---
title: "Each Module Needs A Named Gate Owner"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04, immediately after closing the rule-verifiability
inventory. Asked "what is still unwired?" and found `:androidApp:detekt`.

**Status: OPEN**

**Tracked as:** #461

**Symptom:** `androidApp/build.gradle.kts` has had a `detekt { }` block with
`ignoreFailures = false` and `androidApp/detekt-baseline.xml` (9 entries) since
the module was added. **No gate ever invoked the task** — not `check.sh`, not
`ci.yml`, not the `justfile`. It runs clean (0 findings, ~16 s).

This is the same defect class as `no-direct-dispatchers` and
`user-scoped-repository`: a check that is fully configured, looks authoritative,
and has never executed. The difference is only that this one happens to be
satisfied, so nothing ever went red to make anyone curious.

**Resolved in the 2026-10-04 change:** `:androidApp:detekt` added to both
`check.sh` and the `Run detekt` CI step.

**Try next — the general form of this problem.** `:mcp-server:detekt` sits in
the advisory `mcp-server-check` job and is the last unwired module-level gate.
A grep for `:detekt` across the build files will find every configured task;
each one needs a name in a gate or it is decoration. Worth doing as a
deliberate sweep rather than waiting for the next instance to be discovered by
accident — the cost of a miss is unbounded, since the check is assumed to be
running.

---
