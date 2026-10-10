---
title: "Scenario Result Missing For A Claiming Commit Is Not A Failure"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "scenario-results-are-authoritative-in-ci"]
---

**Status:** CLOSED — tracked GitHub issue is closed

**Tracked as:** #298
**OpenSpec change:** `openspec/changes/scenario-results-are-authoritative-in-ci/`
**Supersedes:** #149 (closed — remaining work captured in #298)

**Found in:** the scenario traceability layer
(`2026-10-05-scenario-test-cases-in-kiwi.md`), while wiring its result matrix into
CI. Not a regression — a requirement that was never written down.

**Why it is deceptive:** everything about this looks finished. The layer has a
result matrix, four outcomes, a green gate and a committed coverage matrix. The
matrix even renders the outcome that matters — a claimed target with no result at
this commit — and *nothing acts on it*. A reader scanning the table cannot tell
whether a cell means "verified" or "nobody looked".

This is the same failure class as `-Ptest.tags=fast,slow` selecting 16 of 218 test
classes for months, and as the three faces already pinned in
`openspec/specs/test-execution-integrity`. It is the fifth.

**Already ruled out:** a floor in `config/docs/kiwi-gaps-baseline.txt` is the
wrong instrument. Polarity differs — for a class, *never run* is the failure; for
a scenario, *never run* is normal (it may be new, or its target may be a different
CI job) and *missing from a commit that claims it* is the failure.

**Try first:** pin what "this build claims the target" means before writing the
check, because the two requirements are unimplementable until that is decided.
Emitting "attempted, not merely present" per target is the enabling change; the
current signal cannot tell "ran and produced nothing" from "was never run".
