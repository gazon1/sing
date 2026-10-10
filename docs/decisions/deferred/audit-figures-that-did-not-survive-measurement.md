---
title: "Audit Figures That Did Not Survive Measurement"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — a correction, not a work item.** The three wrong figures were corrected at the source. Kept in this file because the lesson is the reusable part: an audit is a hypothesis list, and a claim that cannot be confirmed cheaply should be labelled unverified rather than counted.
**Found in:** 2026-10-04 rule-verifiability inventory, while re-checking the
audit's claims by execution rather than by reading code.

**Symptom:** three figures in the original audit were wrong, and acting on them
unverified would have caused damage. Recorded so the next reader does not re-import
them from the same source.

- **`NoRunCatchingInSuspend` was listed as a vacuous rule.** It is not. It is
  registered, configured, has a passing test, and is `active: false` *on purpose*
  pending a migration. "Inert" and "switched off" look identical from a distance
  and need opposite responses.
- **"109 long delay sites"** — the repository has **23** `delay(` call sites in
  total, across `shared/src` and `desktopApp/src`.
- **The 500 ms `NoRealDelayInTest` threshold was read as an accident.** It is a
  documented escape hatch for `stateIn(WhileSubscribed(5000))` VMs, which
  `TestScheduler` cannot advance past. It is now a named constant with that
  reason attached, so the next reader sees intent rather than a magic number.

**Lesson:** an audit is a hypothesis list. The value of running the gates was
never that the audit would be right — it was that executing the claims would
settle them. Every claim in a review should carry the command that confirms it,
and a claim that cannot be confirmed cheaply should be labelled unverified rather
than counted.

---
