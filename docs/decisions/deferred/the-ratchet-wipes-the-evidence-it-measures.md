---
title: "The Ratchet Wipes The Evidence It Measures"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "detekt-tooling-honesty"]
---

**Status: CLOSED** (2026-10-05) — the ratchet now refuses to report a floor from a run that
executed nothing. The underlying anomaly is #138's and stays open.

**Tracked as:** [#146](https://github.com/gazon1/sing/issues/146) ·
`openspec/changes/detekt-tooling-honesty/`

**Found in:** 2026-10-05, while reasoning about #138's leading hypothesis — that `jvmTest` came back
`FROM-CACHE` after `just cr`'s kover wipe.

**Symptom.** `coverage-ratchet` wipes every module's `kover` directory and then runs `koverReport`.
Wiping stale `.bin` is right. But it does not invalidate the *test task*, and `:shared:jvmTest` can
be served `UP-TO-DATE` — re-executing nothing, contributing no fresh `.bin`, and producing a thinner
report than the truth. **A measurement gate that deletes the evidence it is about to measure can be
right for the wrong reason.**

**Why it is not just #138.** #138 asks why one file shows 4/18 and names the cache hypothesis as the
cheapest to rule out. This is the finding that the ratchet can *manufacture* the condition it is
investigating, and it applies to all nine floors. The live part: the `feature/calendar_sync` floor
was adopted to the measured 23.54% with a note saying it is a loan against #138 — if the measurement
came from a cache-served run, the floor was adopted from a number the code never produced.

**Already ruled out.** Not a claim that any current floor is wrong. The nine floors all *rose* when
re-baselined, which is the right direction; the point is that a cache-served floor would have looked
identical in the same run.

**Fix.** `scripts/check-coverage-measurement.py`, wired between the Gradle run and the ratchet
comparison. The recipe captures the build log and reads it back: if a test task in that log was
`UP-TO-DATE`, `FROM-CACHE` or `NO-SOURCE`, or if **no test task appears at all**, the ratchet refuses
to compare floors. 25 self-tests, no Gradle.

**The first version was wrong in both directions, and the run on the real tree is what showed it.**
`.*[Tt]est$` matches AGP's resource-processing tasks — `convertXmlValueResourcesForJvmTest`,
`generateResourceAccessorsForAndroidHostTest`, `javaPreCompileDebugUnitTest` and five more — none of
which runs a test. On the first real `just cr` it reported **19 findings, one of them real**. The
match is now anchored (`test`, `jvmTest`, `test<Variant>UnitTest`).

The same run showed `NO-SOURCE` was also wrong as a *failure*. `:androidApp:testDebugUnitTest
NO-SOURCE` is a module with no unit tests, and failing the whole ratchet over it blocks everyone
forever over a fact the coverage report already states honestly as 0%. It is a warning now. The
distinction that survives: a **cached** task was in the graph, should have contributed, and did
not — that is #146; a task with **no sources** never had anything to contribute.

This is the repository's own lesson applied to my own work, and it is worth stating plainly: a gate
that fires on correct code is the same failure as one that fires on nothing, wearing the opposite
mask. The fixture tests all passed while the gate was unusable in practice — the log I had
imagined was tidier than the log Gradle prints. **The gate was proven on a real run, not on the
examples I wrote for it.**

**It took three passes against real logs, and each pass found a different wrong answer:**

1. `.*[Tt]est$` matched AGP's resource tasks — 19 findings, 1 real.
2. `NO-SOURCE` as a failure blocked on `:androidApp:testDebugUnitTest`, a module with no unit tests.
   And `UP-TO-DATE` on a *sibling* task in the same module is the same fact spelled differently:
   `:androidApp:test` is `UP-TO-DATE` for the same reason `testDebugUnitTest` is `NO-SOURCE`. The
   gate judged a module that has no tests as though it had tests that failed to run.
3. The "module has no tests" exemption needed a second condition, or it becomes a standing pass for
   any module with a `NO-SOURCE` sibling. The condition is that no task in the module may be
   `FROM-CACHE`: a cached task *proves* the module has tests, because it ran once and produced
   outputs worth restoring. `UP-TO-DATE` alone is ambiguous and reads as benign only next to a
   `NO-SOURCE` sibling.

The final rule is scoped per module rather than per task for that reason. 29 self-tests, and the
two that matter most are the real logs: a `RERUN=1` run passes, and a cached run fails.

The override is also honest about what it did: `--allow-cached` prints *passed with warnings*, not
*the test tasks executed*. The first version printed the latter, which is false and is the same lie
one level up.

**Issue #86 then reproduced itself, one line away.** The recipe documents `just cr RERUN=1` — and
that form does not assign in this environment; the token arrives as a positional argument, the flag
is silently absent, and the run still succeeds while measuring less. Which is precisely the failure
this change exists to prevent, delivered by the very syntax used to prevent it. The recipe now
rejects any `NAME=value` argument with a message naming the environment form, and both are written
`RERUN=1 just cr`. It is worth noting how cheap this was to miss: the run *looked* fine.

The recipe also learned that a literal double-brace pair inside a comment is a `just` parse error,
because `just` interpolates it. That is a two-minute trap with an error message that points at the
comment rather than the rule.

**Deliberately not chosen:** making the wipe invalidate the test task. Forcing `--rerun-tasks` on
every ratchet run would cost a full recompile, and a gate that is slow enough to be skipped is a gate
that gets skipped. Refusing to report is the cheaper half of the same honesty.

**Try next.** #138, with `just cr RERUN=1`. The gate now makes the cache case loud; whether it was
the cause is still #138's to answer, and the `feature/calendar_sync` note must be deleted or
re-adopted in the same commit, as that note already requires.

---
