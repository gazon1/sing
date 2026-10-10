---
title: "Version Gate Breadcrumb Has No Test"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "failure-visibility", "failure-visibility"]
---

**Status: CLOSED** (2026-10-05) — the missing test found a real defect on its first run.

**Tracked as:** [#144](https://github.com/gazon1/sing/issues/144) ·
`openspec/changes/failure-visibility/` (REQ-3)

**Found in:** 2026-10-05, re-reading `openspec/changes/failure-visibility/tasks.md` against the tree
while deciding whether that change could be archived.

**Symptom.** `a068b432` added the fail-open breadcrumb to `AppVersionGateViewModel` — when a
remote-config read throws, the gate admits the user on defaults and leaves a record saying it did.
The code shipped. The test did not, and `tasks.md:12` says so in its own words: *"a breadcrumb
asserted only by reading the code is not a breadcrumb."*

**Why it matters.** The report and the breadcrumb are two separate calls emitted from one `catchTo`
block. A regression that drops the report reinstates #126; one that drops the breadcrumb makes the
gate fail open invisibly, which is the original defect wearing the fix's clothes; one that swaps the
keys leaves both halves present but no longer greppable together.

**Already ruled out.** Not a missing harness — `AppVersionGateViewModelTest` had 7 cases and already
drove the failure path. This was an addition to a suite that existed.

**What the test found.** A recording port exposes the ordered log, not just the two lists, and that
is the whole point: the backend attaches the breadcrumb buffer to a report **as it stands when the
report is made**. The shipped code wrote the bypass from the funnel's error handler, which runs
*after* the report — so the record rode on the next event, and during a config outage there is no
next event. The gate failing open was exactly as invisible as it had been before the record was
added; the record was merely attached to the wrong event.

Two tests failed on the first run, one per route. The returned-failure route (`refresh()`) had the
same defect and had been hand-rolled around it, because a returned failure never reaches the
funnel's error arm — the ViewModel reported and breadcrumbmed it itself.

**Fix.** An explicit pre-report step in the funnel, defaulted to empty so every other call site keeps
"an error handler's breadcrumb cannot jump ahead of its report". The returned-failure route now goes
through the same funnel, so the two cannot drift apart again. 5 new cases, 12 total.

**Try next.** Nothing. Kept for the reusable half: a fake that records *one ordered log* rather than
two lists, because a reversed pair and a correct pair produce identical two lists.

---
