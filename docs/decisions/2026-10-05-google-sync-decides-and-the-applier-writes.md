---
date: 2026-10-05
slug: google-sync-decides-and-the-applier-writes
status: accepted
---

# Google sync decides; a separate applier writes. And it runs on desktop.

## Context

`GoogleSyncEngine` computes a per-field 3-way merge and was written to be independently
testable: a fake event source, three in-memory DAOs, no task table in sight. Its own KDoc
says it *"does not touch tasks"* and that the task applier is *"separate work rather than
more of this."*

That left the feature in the worst possible state to ship: every merge decision was correct
and **none of them reached anything**. A title typed on a phone would be recorded as a
remote change and never written to the task, because the only thing reading the merge's
output was a counter. This is the `find-unwired-surfaces` failure mode in its purest form —
code that compiles, is tested, and is called by nothing.

Two decisions close the gap.

## Decision

### 1. `GoogleTaskApplier` is a separate class the engine calls

Not more of the engine, and not a post-pass that re-derives the merge. The engine already
holds the three inputs — local task, remote event, ancestor shadow — and the merge is not
cheap to reconstruct; a post-pass would either recompute it (duplicating the decision logic
in two places, free to drift) or be handed the results (which is just a parameter).

So the split is on **who writes**, not on **who decides**:

```
GoogleSyncEngine                     GoogleTaskApplier
──────────────────                   ──────────────────
decide  BidirectionalMerge   ──────▶  apply FieldOutcome.ApplyToTask → Task
decide  patch() to Google     ◀──────  (the other direction, on its own)
stage  foreign events        ──────▶  create Task + first shadow
```

`GoogleEventTaskMapper` holds the translation as a pure `object` — no clock, no I/O — so the
awkward cases (all-day, untitled, cleared field, timezone) are reachable from a test without
constructing a `Task`.

**The field set is explicit.** `TaskEdits` carries a `changed: Set<TaskField>` *alongside*
nullable values. Without it, `ApplyToTask(null)` — the user cleared the description on their
laptop — is indistinguishable from "nothing to do", and the two opposite instructions both
look like an absent field. Two of the applier's tests exist only to pin that.

**A rename must not move a reminder.** `TaskEdits.movesTime` gates reminder rescheduling on
time fields alone. A reminder shifted by the length of a new title is a bug that arrives as
"notifications fire at random times", long after anyone remembers the rename.

**Reminders keep their offset.** The delta between the old and new due datetime is applied,
so "10 minutes before" stays ten minutes before when the task moves from Tuesday to
Thursday. Recurring reminders are left alone: their `fireAt` is the next occurrence of a
pattern, and rewriting it from a new due date corrupts the series rather than moving it.

**Import creates no reminders.** Importing someone's calendar must not decide, unasked, that
a dentist appointment now nags them.

### 2. Google sync runs on desktop, and is triggered per-cycle

The question was left open because `CalendarSyncOrchestrator` is started only on Android.
The answer is that the Google pass is **pure network plus Room** — an HTTPS call and local
rows. Every platform-specific piece of the *system* calendar sync (ContentResolver,
CalendarProvider, `READ_CALENDAR`) is absent by construction. The only thing it needs from
the OS is "wake up later".

So `GoogleSyncPeriodicTrigger` is a second seam mirroring `SyncPeriodicTrigger`, bound to
WorkManager on Android and to the existing `DelayLoopSyncPeriodicTrigger` on the JVM.
Excluding desktop would ship a sync that works on a phone and silently does nothing on the
machine the user is sitting at.

**`isConfigured()` is re-read every cycle, not once at arm time.** Connecting a Google
account is an ordinary thing a user does ten minutes after launch. A trigger that decided at
startup would either never start or never stop — and neither failure is visible: the loop
runs, syncs nothing, and reports success.

**`GoogleSyncCoordinator` is a `factory`, not a `single`.** It takes a `UserId` *value*, so
a singleton resolved at startup would capture `UserId.anonymous` and decline every pass for
the life of the process. This is the same per-profile reasoning that already forced
`CalendarEventSource` and `GoogleSyncEngine` to be factories.

## Rationale

The applier split is the difference between a feature that works and one that appears to.
The engine's tests could all have passed while the feature did nothing at all; the applier's
tests are the ones that would have caught it.

Choosing per-cycle `isConfigured` over arm-time is the same class of decision as the
`nextSyncToken` rule in the engine: prefer asking "what is true now" over asking "when did we
last look", because the thing being asked about can change underneath the answer.

## Consequences

- `GoogleEventTaskMapper` is pure and exhaustively testable; `GoogleTaskApplier` needs only
  the three repository ports and the two DAOs.
- A `Task` edit that the merge resolved as `Push` or `Conflict` is **never** applied to the
  task. Applying either would overwrite the user's own edit with the value the merge
  deliberately kept — the exact inverse of its decision.
- `taskRepository.update` returns a `Result` of its own. The wrapper only reports whether the
  call *threw*, so checking the outer layer alone reads every business rejection as a
  successful write and then moves the reminders for a task that was never updated. Both
  layers are inspected; there is a test for it.
- Desktop and Android now both poll Google every 15 minutes when configured. `15` is
  WorkManager's documented minimum for periodic work, and both platforms share the constant
  so "how often does Google sync run" has exactly one answer in the codebase.
- The Google pass writes **no** sync status. `CalendarSyncRepository.setStatus` backs the
  *system* calendar's status, which the screen renders in the system half; recording a Google
  pass there would show a confidently wrong answer in the one place the user has learned to
  trust. The Google half needs its own status surface, deliberately not invented here.

## Links

- `sync/GoogleSyncEngine.kt`, `sync/GoogleTaskApplier.kt`, `sync/GoogleSyncCoordinator.kt`
- `domain/logic/GoogleEventTaskMapper.kt`
- `work/GoogleSyncPeriodicTrigger.kt`, `work/DelayLoopGoogleSyncPeriodicTrigger.kt`,
  `work/AndroidGoogleSyncPeriodicTrigger.kt`, `work/GoogleSyncWorker.kt`
- `2026-10-05-google-calendar-two-ports-and-local-wins.md`
- `2026-10-05-google-oauth-hybrid-kmpauth-plus-own-token-exchange.md`