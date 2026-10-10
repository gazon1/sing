---
title: "Maestro Results Are Produced And Discarded In Ci"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "scenario-results-are-authoritative-in-ci"]
---

**Status:** CLOSED — tracked GitHub issue is closed

**Tracked as:** #151
**OpenSpec change:** `openspec/changes/scenario-results-are-authoritative-in-ci/`
**Note:** #150 (JUnit output discarded) was closed; remaining work is the Android tier unverified end-to-end, captured in #151. #299 was closed as duplicate of #151.

**Found in:** the same layer, while asking why the Android column of the result
matrix is permanently empty.

**Why it is deceptive:** the Android flows *do* run in CI, and they *do* produce
the JUnit XML the normaliser reads — `scripts/run-maestro.sh` now passes
`--format=JUNIT --output=build/maestro-results`. The output is then discarded:
the only artifact that job uploads is Maestro's own debug report, and only
`if: failure()`. Twenty of 59 flows carry the `smoke` tag, so the scenario flow is
among them.

So the Android column of the result matrix is not a coverage hole. It is a hole
in the plumbing, and it is indistinguishable from the former when you look at the
table.

**Already ruled out:** simply uploading the directory with `if: always()`. The
flows run in a *different workflow* from the job that builds the matrix, so making
the data available does not put it in the matrix, and cross-workflow artifact
handoff is its own problem. The honest minimum is for the matrix header to state
which targets this build covered.

**Try first:** run one scenario flow on a host with a working emulator and check
whether the result reaches the matrix at all. That single run settles the join
(`file` attribute, its base, whether the sheet's confirm is reachable) and every
remaining question here is downstream of the answer. Note that no flow has yet
produced a result in this environment, so the Android tier is unverified end to
end — the reporter emitting a `file` field is confirmed in the Maestro 2.10.0 jar,
but the join is inference until a real run exercises it.
