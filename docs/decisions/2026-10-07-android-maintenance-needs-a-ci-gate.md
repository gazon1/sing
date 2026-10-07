---
date: 2026-10-07
slug: android-maintenance-needs-a-ci-gate
status: accepted
---

# `:androidApp:assembleDebug` is the only thing that compiles `androidMain`, and CI is down

While merging the Google-sync work I hit a compile error in
`AndroidBackgroundWorkScheduler.kt` — code from commit `276a70e3`, not mine. I reproduced it
in a clean worktree at `origin/main` with none of my changes present, so it was already there.

The cause: WorkManager 2.10 takes `java.time.Duration`, while `JobSchedule` speaks
`kotlin.time.Duration`. The periodic branch passed a `Long` where a `Duration` was expected and
the one-shot branch passed a `kotlin.time.Duration` where a `java.time.Duration` was expected.

## This is the second time in three days

`739e7c05` ("main did not compile, and the gate that would say so never run") fixed
`FileRevealer.android.kt` failing to compile for exactly the same reason. Both times:

- the broken file was in `androidMain`
- the daily loop is `:shared:jvmTest` plus detekt, **neither of which compiles the Android
  target**
- `:androidApp:assembleDebug` is the only thing that sees it, and it is late in a gate that
  had not run, because CI has been unable to start a job since account billing failed

So the pattern is not "someone made a typo". It is that **a whole source set has no
compilation check in the loop anyone actually runs**, and defects accumulate there.

## Why the fix converts rather than changes the domain type

`JobSchedule` is in `commonMain` and must not depend on a platform library, so the conversion
belongs at the WorkManager call. Two rules, applied identically in both branches:

1. keep a domain type in the domain (`MIN_PERIODIC_INTERVAL` is a `kotlin.time.Duration`, so it
   can be compared against `JobSchedule.Periodic.interval`),
2. convert to `java.time.Duration` only at the WorkManager call.

Writing the floor as a millisecond `const` is what made the comparison awkward in the first
place: comparing a `Long` against a `Duration` forces a conversion on one side only, which is
how the second error appeared.

## The generalisable rule

> A source set with no compile step in the loop you actually run is not a source set with
> "occasional" mistakes — it is one where mistakes are invisible until someone runs the gate by
> hand, which is what happened twice.

The fix for the code is two conversions. The fix for the *pattern* is either restoring CI or
adding `:androidApp:compileDebugKotlin` — a cheap compile-only task — to whatever loop runs
locally. `assembleDebug` is not that task: it links and packages an APK, so it is expensive
enough that skipping it under time pressure is rational. A compile-only task is not.

Recorded because the next person to hit this will otherwise assume it is their merge.

## Links

- `core/work/AndroidBackgroundWorkScheduler.kt` — both conversions
- `739e7c05` — the previous instance, same cause
- `2026-10-06-google-sync-failures-were-shaped-like-skips.md` — the work this was found during