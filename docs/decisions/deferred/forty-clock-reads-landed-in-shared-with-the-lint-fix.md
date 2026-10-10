---
title: "Forty Clock Reads Landed In Shared With The Lint Fix"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED** — violations resolved. `:shared:detekt` reports 0 NoDirectClockSystem violations on a clean rerun. The fix (`6899121a`) removed the blanket `@file:Suppress` and the file was brought back to green. The backlog entry body documents the root cause and the alternative (inject clock through fake constructors).

**Symptom:** `:shared:detekt` reports 40 `NoDirectClockSystem` violations, all in
`shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
(lines 625, 645, 693, 721, 1149 and others). detekt is `ignoreFailures = false`, so
`check.sh` cannot reach step 20.

**Why it is not a regression of the work that found it.** `6899121a` removed a
blanket `@file:Suppress` that was switching the rule off for 38 clock reads, which
is exactly the defect `check-suppression-intent.py` now prevents. The fix is
correct on its own terms; what was missed is bringing the file back to green after
it. Verified by stashing the session's work and running `:shared:detekt` on the
clean tree at `bce4873a` — the same 40 findings.

**Try next:** inject a `kotlin.time.Clock` through the fake's constructor and let
each test pass the clock it already has. A fake reading the wall clock is a source
of both test flakiness and the "green here, red on a slow host" class of bug, so
this is the rule working rather than the rule being inconvenient. The alternative
— a `@file:Suppress` with a written reason, now legal under the new gate — accepts
the debt, and 40 is a large amount to accept in one commit. Not fixed here because
it touches every test that constructs one of these fakes, which is not a change to
carry inside a commit about crash reporting and a repository rename.

---
