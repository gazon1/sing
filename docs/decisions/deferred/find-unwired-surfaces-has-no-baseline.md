---
title: "Find Unwired Surfaces Has No Baseline"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `scripts/find-unwired-surfaces-baseline.txt` exists with 11 entries, the script returns 0 only when the baseline filter clears every finding, and `ci.yml:225` runs it blocking. A scan that finds nothing and a scan looking in the wrong place are now distinguishable.

**Found in:** MR-4, while wiring the script into the workflow.

**Symptom:** the script exits 1 whenever anything is reported, and the one
standing finding (`SyncConfigScreen`) is a known, documented product question.
So the script can never gate a check, and "no new findings" is verified by
reading output manually — which means it will not be.

**Status: RESOLVED.** `SyncConfigScreen.kt` was deleted — the sole standing finding
is gone. Phase 1.2 (PR-2) added `find-unwired-surfaces-baseline.txt` and
wired `find-unwired-surfaces` as a blocking CI gate. The script exits 0 when
baseline is current and new findings exist.

---
