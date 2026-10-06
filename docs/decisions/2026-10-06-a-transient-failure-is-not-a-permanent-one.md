---
title: A transient failure is not a permanent one, and three places guessed
date: 2026-10-06
tags: [sync, calendar, error-handling, ui-state]
status: accepted
---

## Context

Fixing the pull-apply path (see the commit titled *a write that did not happen
was reported as a lost race*) turned on splitting one catch into two: bytes that
cannot be decoded are `Skipped`, and a write that did not happen is `Failed`.
The split works, but the reasoning behind it turned out to apply somewhere the
pull path never touches.

`CalendarSyncViewModel` sets `isSupported = false` on **any** failure of
`CalendarProviderPort.getAvailableCalendars`, while the KDoc on that field
promises a failure "with an unsupported-platform error".

On Desktop this is harmless. `NoopCalendarProvider` fails with
`UnsupportedOperationException` and nothing else, so the inference happens to be
correct. On Android the port is `AndroidCalendarProvider`, which wraps
`contentResolver.query` in `runCatchingCancellable`. A revoked `READ_CALENDAR`
permission surfaces as `SecurityException` and a sick content provider as
`SQLiteException`. Neither means "this platform cannot do calendars", yet both
set `isSupported = false`, and `SettingsSwitchRow` responds by dropping its
click handler — the whole calendar-sync control goes dead for that user, with no
message separating "your device cannot" from "you denied access", and no recovery
short of an app restart.

The vocabulary for judging permanence already exists in this repository, on the
other side. `SyncProtocol` centralises its terminal codes in one set precisely
because "retrying a permanent error costs a retry budget and ends in the dead
letter store", while "dropping a transient error instead loses the user's edit
with no trace at all". The asymmetry is a deliberate, written choice. The
calendar port has no such table, so its single failure arm had to guess.

## Decision

The calendar capability signal becomes typed rather than inferred. A port failure
carries a reason the UI can act on, so `isSupported` is read from that reason and
not from the bare fact that something failed.

We do not keep the current shape with a narrower condition, because the exception
type is not the contract — it is one implementation's way of producing the signal.

## Why guessing permanence is the defect, wherever it appears

In the pull path, guessing cost a user's edit. In the calendar path, it costs a
user's working feature when they decline a permission. Both are the same error of
reasoning: an untyped failure leaves code with no basis to distinguish "will
never work" from "did not work this time", so it takes the pessimistic reading —
and the pessimistic reading is the expensive one, because it is either the user's
data or their feature.

The asymmetry is what makes this worth changing rather than documenting. Reading a
permanent error as transient costs a retry budget and ends in the dead letter
store, where a human can see it. Reading a transient error as permanent loses the
thing outright. The sync side wrote that trade-off down; the calendar side has to
earn the same judgement, and it cannot without a typed reason.

## Consequences

- `CalendarProviderPort` gains a typed failure and both actuals produce it. That is
  a contract change on a port rather than a UI change, so it needs its own
  OpenSpec change before the code moves.
- `CalendarSyncUiState.isSupported` stops being set from the mere presence of a
  failure. Anything currently relying on "any failure disables the toggle" changes
  behaviour; the Desktop noop path is the only caller that should still reach
  `false`.
- The pull-path fix and this one stay separate changes. They share a shape, not a
  mechanism, and the pull path is already fixed and verified.

## Not decided here

Recorded because it was found while reading the same code, and left alone
deliberately rather than folded in.

After the pull fix, `ApplyOutcome.Conflict` is constructed **nowhere** in
production. The pull path applies a remote document wholesale and performs no
field-level merge — the per-field HLC merge that could produce a lost field lives
on the push side — so "a field lost to a newer one" cannot occur on this path at
all. `PullStep.Done.conflicted` and the `conflicts` field on `PullSummary` are
therefore structurally unreachable, and `SyncEnginePullTest` only reaches them by
registering a handler that returns `Conflict` directly.

A counter that cannot become non-zero is the same kind of lie the pull summary
used to tell, so removing the arm and the field is the honest end state. It is not
done here because `PullSummary` is a public type read outside `core/sync`, so
deleting a field is a contract change that wants its own decision.

## Links

- `3d6f2025` — the outbox decode guard, from the same reading
- `ebb3c1dc` — the pull-apply fix this reasoning was generalised from
- `CalendarSyncViewModel.kt:367-373` — the `isSupported = false` arm
- `AndroidCalendarProvider.kt:48-71` — the failures that are not "unsupported"
- `SyncProtocol.kt:125,201-208` — the terminal-error table, and the asymmetry
- `SyncEngine.kt:145` — `ApplyOutcome.Failed`, whose KDoc already names this case