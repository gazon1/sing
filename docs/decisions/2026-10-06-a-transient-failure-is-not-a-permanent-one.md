---
title: A transient failure is not a permanent one, and one of three guesses had no reader
date: 2026-10-06
tags: [sync, calendar, error-handling, ui-state]
status: superseded
status-was: superseded-in-part
superseded-by: 2026-10-06-a-transient-failure-is-not-a-permanent-one
---

## Correction

The version of this note written on 2026-10-06 was wrong in its central claim, and
is corrected here rather than quietly replaced.

It asserted that a revoked calendar permission on Android set `isSupported = false`
and took the whole calendar-sync control down with it. Tracing the code says
otherwise. `CalendarSyncSettingsScreen` passes `permissionRequester.isSupported` into
its panel — not `state.isSupported`. `CalendarPermissionRequester` is an existing port
that answers the platform question statically: `false` on Desktop, `true` on Android.
The permission case is handled separately again, by `hasPermissions`, which offers the
system dialog that fixes it.

So `CalendarSyncUiState.isSupported` was written in exactly one place and read in none.
Its only readers were the three assertions of the test that shipped with it in #214.
The control never went dark; the flag simply never reached a screen.

The reasoning below about permanence is still sound and still applies to the pull
path. What changes is the conclusion: the third guess had no reader, so the fix is
removal rather than a typed error. Building a typed port failure to feed a
dead field would have been work for a consumer that does not exist.

## Context

Fixing the pull-apply path (see the commit titled *a write that did not happen
was reported as a lost race*) turned on splitting one catch into two: bytes that
cannot be decoded are `Skipped`, and a write that did not happen is `Failed`.
The split works, but the reasoning behind it turned out to apply somewhere the
pull path never touches.

`CalendarSyncViewModel` set `isSupported = false` on **any** failure of
`CalendarProviderPort.getAvailableCalendars`, while the KDoc on that field
promised a failure "with an unsupported-platform error".

On Desktop the inference happens to hold: `NoopCalendarProvider` fails with
`UnsupportedOperationException` and nothing else. On Android the port is
`AndroidCalendarProvider`, which wraps `contentResolver.query` in
`runCatchingCancellable`, so a revoked `READ_CALENDAR` permission surfaces as
`SecurityException` and a sick content provider as `SQLiteException`. Neither
means "this platform cannot do calendars", and neither reached a user, because
nothing read the flag.

That is the cheaper half of the defect, and it is worth being precise about which
half it is: the flag was a **wrong signal aimed at nobody**. Had the screen read
it, the same code would have disabled a working feature for a user who merely
declined a permission.

The vocabulary for judging permanence already exists in this repository, on the
other side. `SyncProtocol` centralises its terminal codes in one set precisely
because "retrying a permanent error costs a retry budget and ends in the dead
letter store", while "dropping a transient error instead loses the user's edit
with no trace at all". The asymmetry is a deliberate, written choice.

## Decision

`CalendarSyncUiState.isSupported` is removed, along with its writer and its three
assertions.

The platform gate already has an owner and that owner is better: `CalendarPermissionRequester`
answers the question statically, per platform, without making a call that can fail.
`hasPermissions` answers the permission question separately and can offer the dialog
that resolves it. Wiring the state flag in would have added a third gate — and a
worse one, because it would decide a permanent fact from a call that can fail
transiently.

So the typed port failure this note originally prescribed is not built. It would
have given a reason to a signal with no reader.

## Why guessing permanence is the defect, wherever it appears

In the pull path, guessing cost a user's edit. In the calendar path it cost nothing,
because the guess went nowhere — which is the more instructive half. The same code,
had anything read it, would have cost a user their working feature when they declined
a permission. Both are the same error of reasoning: an untyped failure leaves code with
no basis to distinguish "will never work" from "did not work this time", so it takes
the pessimistic reading — and the pessimistic reading is the expensive one, because it
is either the user's data or their feature.

The asymmetry is what makes this worth changing rather than documenting. Reading a
permanent error as transient costs a retry budget and ends in the dead letter store,
where a human can see it. Reading a transient error as permanent loses the thing
outright.

The calendar case also shows why a wrong signal aimed at nobody is still worth
removing. It cost nothing while unread, and it was one edit away from costing
something. The field was a leftover from a design the screen had already replaced,
kept alive by a KDoc and three assertions that documented a signal no user could
receive.

## Consequences

- `CalendarSyncUiState` no longer carries a capability verdict. The platform gate is
  `CalendarPermissionRequester.isSupported` and the permission gate is
  `hasPermissions`, both read by the screen and both answered without a fallible call.
- `CalendarProviderPort` is unchanged. No typed failure was added, because nothing
  consumes one.
- The remaining gap is named rather than fixed: a provider failure and an empty
  calendar list still both leave `availableCalendars` empty with nothing to explain
  it. The `googleError` KDoc names exactly that trap for the Google half. The system
  half is shielded in practice by the permission gate, which hides the list until
  permissions are granted — but a failure arriving *after* that gate still shows an
  empty list. Closing it needs somewhere in the UI to say so, which is a product
  decision rather than a defect fix.
- `scripts/find-unwired-surfaces.py` did **not** report this field. It flags symbols
  with no call site; a state field read only by its own test is neither. This looked
  like a cheap gap to close — "a property whose only readers are in `*Test.kt`" is a
  static signature, and the shape is narrow.

  **It was attempted and it does not work.** The rule counts identifier occurrences
  by name, and property names are not unique. `isSupported` is declared on
  `CalendarPermissionRequester` as well, and the screen's read at
  `CalendarSyncSettingsScreen.kt:91` is *that* property — so a name counter
  attributes it to the dead field and the rule stays silent on the exact case it was
  written for. Restricting the rule to names declared exactly once avoids the
  collision and also excludes this field, which is the only one that mattered.
  Counting class names works; counting property names does not, because the reads are
  qualified by a receiver that name-based counting cannot see.

  So the detector is left alone. A rule that fires on nothing is worse than no rule:
  it is a green gate that proves nothing, which is the failure mode this repository
  cares most about. Closing this class properly needs receiver-aware resolution —
  a type-resolving check, or Compose-level tests that assert what the screen renders.
  The second is the more honest of the two, because the defect was always about what
  a user sees, not about which field exists.

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
- `CalendarSyncSettingsScreen.kt:91,145` — where the platform gate actually comes from
- `CalendarPermissionRequester.kt` — the port that answers it, statically per platform
- `CalendarSyncViewModel.kt` — the `onFailure` arm that no longer decides anything
- `SyncProtocol.kt:125,201-208` — the terminal-error table, and the asymmetry
- `SyncEngine.kt:145` — `ApplyOutcome.Failed`, whose KDoc already names this case