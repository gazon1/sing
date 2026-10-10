---
title: "A Filtered Test Run Is Indistinguishable From A Shrunken Suite"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status: RESOLVED 2026-10-07.** The test task writes a run manifest beside the XML, and
`check-test-runs.py` reads it: below the floor, the message now says the counts are not
evidence and names the filter, instead of reporting a regression against a tree where
nothing had happened. Verified end to end — manifest marked partial, most of the XML moved
aside, gate prints the filtered-run sentence. A missing manifest is treated as unknown, not
as filtered.

**Tracked as:** #222 (closed)

**Found in:** 2026-10-07, while running `check-gate-wiring.py` on a tree where nothing
was broken.

`check-test-runs.py` reports the same verdict, with the same message, for two unrelated
situations: the suite genuinely shrank, and someone ran
`./gw :shared:jvmTest --tests 'SomeOneClass'` for a fast loop. The second rewrote
`shared/build/test-results/jvmTest/` with one class's XML and silently invalidated the
evidence that `check-test-runs.py`, `check-coverage.py` and `check-flaky-tests.py` all
read.

Observed, on a healthy tree, after a filtered run of two arch test classes:

```
ERROR: gate 'test-runs' already fails on a clean tree (exit 1). Fix the underlying
failure before trusting its sabotage control.
```

Every gate named in that sentence was behaving correctly. The diagnosis cost is the
defect: it says "fix the underlying failure", and the underlying failure was an ordinary
development command run on the same machine twenty minutes earlier. The same ambiguity
hit `origin/main` in the other direction earlier in the session — `check.sh` never reached
its last steps and nothing in the output said why.

**Not a staleness problem, and must not be regressed into one.** Freshness is handled:
`check.sh` passes `--max-age 21600`, CI passes `--since "$RUN_STARTED"`, and `count()`
returns `None` rather than a passing zero when the newest report predates the window. The
gap is partiality.

**Try first:** have the test task write a run manifest next to the XML — task path,
whether `--tests` was passed, source-set class count — and have the gate read one field
from it, so it can say "this evidence came from a filtered run" instead of "a suite
stopped running". Gradle leaves no such marker today, which is why this is not a
five-line fix to the gate itself.

---
