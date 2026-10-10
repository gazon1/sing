---
title: "No Consecutive Blank Lines Was Never Declared"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #460

**Found in:** 2026-10-04, immediately after `check-rule-intent.py` was wired into a
run that touched documentation. The gate reported exactly one hit.

**Symptom:** `NoConsecutiveBlankLines` produces one finding
(`TimeTrackingSection.kt`, present in `baseline-shared.xml`) and is not named
anywhere in `config/detekt/detekt.yml`. It is running on detekt's built-in default.

Verified present on a clean `HEAD` — not a regression from the B2
`TaskDetailDeps` work.

**Why one finding is worth a backlog entry.** A rule nobody declared is a rule
nobody chose. If a future detekt release changes that default, the baseline stops
matching and the gate fails for a reason nobody can reconstruct. The
`check-rule-intent.py` gate exists to make that class of invisible decision
visible, and this is the first real hit it produced — which is also the proof
that it is doing its job rather than merely passing.

**Try next:** declare it with `active:` and a reason. The honest answer is
probably to fix the file (it is one trailing blank line) and let the count reach
zero, then delete the baseline entry.

Related: `autocorrect-touches-files-outside-the-change` — the same file is one of
the five `--auto-correct` wanted to rewrite.


---
