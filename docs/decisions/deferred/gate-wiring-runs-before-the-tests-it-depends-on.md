---
title: "Gate Wiring Runs Before The Tests It Depends On"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED** — fix committed in `2c7a51f9`. `check-gate-wiring.py` is now invoked after the test tasks it reads, so a fresh clone reaches step 20. The entry body documents the root cause and the fix.

**Symptom:** `check.sh` invokes `check-gate-wiring.py` at step 7 and
`:shared:jvmTest` at step 9. Part B of the wiring check proves each registered
gate *can fail*, and one of those gates — `check-test-runs.py` — reads the JUnit
XML produced by those test tasks. On a tree where the XML is absent or stale (a
fresh clone, or after any `--tests`-filtered run) the check reports

    ERROR: gate 'test-runs' already fails on a clean tree (exit 1)

and `check.sh` exits 1 before reaching the step that would have produced what it
wants. Verified against a clean tree with this session's changes stashed, so it
is not caused by the new tests.

**Already ruled out:** not a false alarm. The gate is correct — it genuinely
cannot demonstrate that `test-runs` fails, because on this tree `test-runs` fails
for an unrelated reason.

**Try next:** move the wiring check after the test tasks. Nothing before step 7
depends on it, and it does not need to run early. The tempting alternative —
having the wiring check skip the `test-runs` control when the XML is absent — is
worse: it teaches the reader that "no results yet" is an acceptable state, which
is the exact reading this project keeps eliminating. Not done here because it
changes what the local gate's exit code means, and that deserves its own commit
rather than arriving as a drive-by.

---
