---
title: "Android Scenario Matrix Is Not Illuminated By Ci"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-06, while restructuring the CI workflows.

**Tracking:** `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the
decision to declare rather than fix, and why, live there. No issue filed yet:
both fixes are scheduled work rather than a defect, and this file already
flags the unbounded-queue problem, so an issue filed now would be filed
into the same place 67 other open entries already sit.

**Status: OPEN — accepted as declared debt, not as a defect.**

18 of 19 scenario specs claim the android target. Exactly one Maestro flow carries
a `scenario:` tag (`TASK-REC-01`), no workflow passes `--maestro` to
`traceability results`, and `:androidApp:connectedDebugAndroidTest` runs in no CI
job. So the android column of the result matrix renders as ⌛ on every commit.

**Why it was left declared rather than fixed here:** both honest fixes are not CI
changes. One is a device-backed instrumentation job on an emulator, which is the
10-minute `assembleDebug` plus a boot, on every PR. The other is tagging 18 flows
with scenario ids and keeping their run non-partial, so every claimed scenario with
a carrier must produce a result. Neither belongs in a restructure whose subject is
which workflow runs what.

**What was done instead:** CI normalises `--targets desktop` only, so the matrix no
longer implies an android measurement it did not take, and the limitation is
recorded in `config/docs/traceability-ratchet.json` under `known_gaps` where a
reader of the artefact will meet it.

**Try next, in this order:** (1) run `connectedDebugAndroidTest` on the E2E
emulator and feed its JUnit into `traceability results`; (2) tag the flows that
already have carriers and switch the nightly to a non-partial android run. Do (2)
without (1) and every claimed android scenario without a tagged flow fails the
nightly, which is the exit-2 rule working correctly rather than a new bug.

**Not to do:** pass `--targets android,desktop` without one of the above. That is
the current state wearing a measurement's clothes.

---
