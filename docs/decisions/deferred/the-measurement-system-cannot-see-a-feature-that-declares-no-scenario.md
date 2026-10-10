---
title: "The Measurement System Cannot See A Feature That Declares No Scenario"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN — the calendar-sync instance is closed, the class is not**

**Tracked as:** none yet; the class-level gap is the argument for a gate below.

**Found in:** 2026-10-07, while writing the scenario specs the Google calendar-sync
feature never had.

**Situation, measured.** `infra/kiwi/traceability/` is a real measurement system: 26 specs,
49 claimed cells, three blocking gates, a one-directional hole ratchet that failed at
`holes: 2 -> 32` on the day it was written. The calendar-sync feature shipped **23
unit-test classes, 8 OpenSpec requirements, and 0 scenario specs** — and therefore
contributed **zero** cells to the matrix. It could not be reported as a gap, because a
system that enumerates what has declared itself cannot report what has not.

The matrix read `holes: 32` the whole time and looked exactly as healthy as it had the
week before, on a different product.

**Already ruled out.** Not a coverage shortfall: the unit tests are good and several guard
invariants that would be expensive to lose (`RecurrenceRuleMapperTest` and
`EventShadowCodecTest` on the byte-identical recurrence rule; `SyncDiffMergeTest` on the
merge). Not a linkage problem either — the scanner found the new carriers first try, and
`traceability validate` exits 0 with holes reported as information by design ("это не
ошибка — это и есть смысл матрицы").

The reason no carrier could exist was upstream of all that: **the panel had zero
`testTags` in 592 lines**, and carriers are only recognised in `desktopApp/src/jvmTest` and
`androidApp/src/androidTest` — `shared/commonTest` cannot carry one, which the 23 existing
tests do. So the feature was not merely unmeasured, it was *unmeasurable*: there was no
address to point a carrier at.

**Why this stays open after the fix.** The calendar-sync instance is closed — 7 specs, 6
desktop carriers, honest `unreachable` on `CAL-SYNC-RECUR-01`, and the floor raised
32 -> 40 with the reason recorded in `traceability-ratchet.json`. What is not closed is
the **class**: nothing requires a feature to declare a scenario, so the next feature can
ship exactly the same way.

**Try next, in this order.**

1. **A gate that a new feature area declares at least one scenario**, failing when a
   directory under `shared/src/commonMain/.../feature/<new>/` appears with no
   `infra/kiwi/scenarios/<area>/`. This is the only step that closes the class. The hard
   part is the exemption list: a feature that genuinely has no user-visible surface should
   be deletable from the list by adding a name to a file, and that file needs its own
   reviewer-visible justification — the same bargain the detekt baseline makes.
2. **The metric that makes it visible without a gate**: `dark_areas` — feature areas with
   production files and zero specs. It cannot fail anything on its own, but it appears in
   the matrix output, so the absence is a *looked-at* number rather than an unasked one.
3. **Reorder the ADR/scenario relationship.** `calendar-sync` got 8 requirements in
   `openspec/specs/` and zero scenarios, and nothing connected the two. If a spec file
   under `openspec/specs/<area>/spec.md` were the thing that demanded scenarios, the gap
   would surface at the moment the requirement was written rather than at the audit.

**Not to do:** raise the hole floor again to make the number smaller. 32 -> 40 was a
*correct* increase: six new scenarios verified on desktop bought an honest accounting of
twelve previously invisible cells. Diluting the number back would restore the exact
condition the ratchet was written to detect.

---
