---
title: "Autocorrect Touches Files Outside The Change"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04, during the B2 `TaskDetailDeps` split, immediately
after adding the `check-rule-intent.py` gate.

**Status: OPEN**

**Tracked as:** #459

`./gradlew :shared:detekt --auto-correct` rewrote **five files that had nothing
to do with B2**: `BackupMigrations.kt`, `LogbookSection.kt` (unused
`java.util.Locale` import), `TimeTrackingSection.kt` (trailing blank line),
`LogBundleExporterTest.kt`, and `EntityMapperCompletenessTest.kt` (33 lines of
re-indentation). All five are baselined debt that had been sitting there.

They were reverted, because a commit titled "split TaskDetailDeps" that also
silently reformats an unrelated test fixture is a commit nobody can review —
and the next person to bisect it would have no way to tell the two apart.

**Why this is worth recording rather than just doing:** `--auto-correct` on a
module-wide task has no idea what the current change is about. It is correct
individually in every case here — that is what makes it dangerous, since
"obviously fine, why not" is the natural reaction to each individual hunk.

**Try this first:** after any auto-correct run, `git diff --stat` and revert
anything outside the stated scope. Cheaper alternative for a large cleanup: run
auto-correct in its own commit, before the real change, so the formatting churn
is already in history.

Related: `EntityMapperCompletenessTest.kt` carries 2 baseline entries for this
file, and `TimeTrackingSection.kt` is the source of the currently-undeclared
`NoConsecutiveBlankLines` finding that `check-rule-intent.py` reports (verified
present on a clean `HEAD`, not introduced by B2). A cleanup commit should declare
that rule rather than leave it on detekt's default.


---
