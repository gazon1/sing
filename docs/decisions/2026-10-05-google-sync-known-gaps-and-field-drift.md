---
title: Known gaps in Google sync, and the two ways this feature drifts
date: 2026-10-05
status: open
status-was: proposed
slug: google-sync-known-gaps-and-field-drift
---

# Known gaps in Google sync, and the two ways this feature drifts

Everything here is **not done**, except where an item says otherwise. It is written down because
each item is invisible from the code, and each one has a shape that has already produced a defect
somewhere in this feature.

> **Updated 2026-10-06.** Items 1 and 3 are **closed** — a Google pass now reports all three
> outcomes, and `GoogleSyncPass` makes the guard testable. See
> `2026-10-06-google-sync-failures-were-shaped-like-skips.md` for what fixing them turned up.
> Item 2's screen-side half was also closed in the same change.

## Open items

### 1. ~~A Google sync pass writes no status the user can see~~ — **closed 2026-10-06**

**Was:** `GoogleSyncCoordinator` deliberately does not call
`CalendarSyncRepository.setStatus`, because that backs the *system* calendar's status and the
settings screen renders it under the system half. Recording a Google pass there would show
"Last synced 14:02" under System Calendar while the Google half said nothing — a confidently
wrong answer in the one place the user has learned to trust.

The consequence was that `googleError` covered **only** `listCalendars()`. A sync that failed
after the calendar list loaded was invisible: the "Sync Now" button did nothing and said
nothing.

**Now:** `CalendarSyncUiState` carries `googleSyncing`, `googleSyncError` and
`googleLastSyncedAt`, all fed by the coordinator's outcome and rendered in the Google half. The
coordinator still does **not** write the shared `CalendarSyncRepository` status — that decision
stands, because the wrong-status problem was real and is still true. The Google half reports
through its own state instead. The success clock advances only on a completed pass.

Closing it exposed two further defects, both recorded in
`2026-10-06-google-sync-failures-were-shaped-like-skips.md`: a failed pass was reported to the
worker as "did not run" and answered with `Result.success()`, and the Google "Sync Now" button
was driving the *system* orchestrator.

### 2. The import window is displayed from a constant, not from configuration

`CalendarSyncSettingsScreen` renders `ImportWindow.DEFAULT` directly, while
`GoogleSyncEngine` and `GoogleCalendarEventSource` each default their `importWindow` parameter
to the same constant independently. Nothing overrides it, so today the constant genuinely *is*
the value — but there are now three places to change and one place to remember.

This was a live example of the "dead state field" problem below: the field that *did* exist on
`CalendarSyncUiState` (`importWindow`) was never assigned, so it always displayed
`ImportWindow.DEFAULT` while looking configurable.

**Why it is still open.** The screen reads the *constant*, not a literal copy of its value, and
says so in a comment. That is the honest arrangement for a value with no setting behind it: the
display and the default cannot disagree today. What is still true is that there are three
defaulting sites, so changing the window means finding all three.

**Wants:** one source. When the window becomes user-editable, it moves to
`GoogleCalendarSettingsRepository` and the screen reads it from state — the same shape as item 1's
fix, and the reason that fix is the template for this one.

### 3. ~~`GoogleSyncCoordinator` has no tests~~ — **closed 2026-10-06**

It held the guard that matters most and was asserted by nothing:

```kotlin
if (userId == UserId.anonymous) return Outcome.Declined("not signed in")
```

`UserId.anonymous` is a value a signed-out session genuinely holds, and every id-bearing store
in this feature is keyed by user. Without that check a signed-out app would sync whatever
credential sits under the anonymous profile's key.

It was untestable because `GoogleSyncCoordinator` took
`engineProvider: () -> GoogleSyncEngine` — a concrete class with four DAO collaborators. There
was no seam to fake.

**Now:** `GoogleSyncPass` — a `fun interface` with one method — is what the coordinator takes,
`GoogleSyncEngine` implements it, and `GoogleSyncCoordinatorTest` asserts the guard, both
declines, the completion, and the failure path that no caller previously distinguished.

### 4. Two dead state fields were removed rather than wired

`CalendarSyncUiState.systemCalendarSupported` and `.importWindow` were declared, read by the
screen, and **never assigned**. They were deleted (see item 2 for what replaced the second).

The gate `find-unwired-surfaces.py` did not catch them because it looks for classes and
functions with no call site, not for properties that are read but never written.

**Still open, and now better characterised.** A prototype for the "read but never written" check
returned 63 candidates whose first entry was a false positive — `ProfileSwitcherUiState.isLoading`
*is* written, on the line after its declaration, and a naive regex misses it. The check is not
hard to write badly; it is hard to write *without* crying wolf, which is why it is recorded
rather than shipped.

Note the limit of the whole family: this class of defect is "looks right, references something
that does nothing", and no reference-counting gate can see it. The two defects closed on
2026-10-06 were found by reading, not by tooling.

### 5. `desiredEventFor` reads one task per event per pass

The push walk now does N single-row reads on every pass, where `desiredEvents()` does one
`observeAll().first()` for the insert case. At calendar scale (hundreds) this is fine. It is
recorded because the asymmetry will look like an oversight to the next reader, and the honest
reason for it — the pull walk needs the *current* task for a merge, while the insert walk only
needs the task set — is easy to lose.

### 6. The task-selection rule was three copies — **closed 2026-10-06**

`!isCompleted && !isTrashed` was written out separately in `CalendarSyncWorker`, in
`GoogleTaskApplier.desiredEvents()`, and in `DirtyHashProvider.hash()`, kept in agreement by a
comment in each saying "this deliberately matches the other". One of the three was in
`androidMain`, so the JVM suite could not have caught a divergence even if a test had tried.

**Now:** `Task.belongsOnACalendar`, one definition in `commonMain`, used by all three. This was
the "configuration duplicated as a literal" pattern below, except the duplicated thing was
business logic rather than configuration — and duplicated business logic is worse, because the
consequence is a task on one calendar and not the other.

## The two drift patterns worth watching

### Positional coupling

`MergeResult.fields` is a `List<FieldOutcome>` whose order is bound to `EventShadow`'s
declaration order. `editsFor` read it with raw indices, so adding a field would have silently
relabelled every outcome after it: renames treated as time changes, no compile error, no test
failure. Fixed by naming the seven accessors on `MergeResult` and documenting the binding in
one place.

**The generalisable rule:** a positional contract needs its indices named at the type that
owns the order, not spelled out again by every caller. The second spelling is where the two
drift apart.

### Configuration duplicated as a literal

`ImportWindow.DEFAULT` is now referenced from three places, and the screen's copy is a
*literal* rather than a read of the engine's configuration. Duplicated configuration is
silent until the two disagree, and then the disagreement is in the user's calendar rather than
in a log.

**The generalisable rule:** when a value is read for display, read it from the thing that
actually uses it. A screen rendering its own copy of a default is a comment that lies the
moment the default changes.

This one has a sharper edge than it looks like. The three copies on 2026-10-05 were not
configuration but *business logic* — which tasks belong on a calendar — and one of them was in
`androidMain`, outside the reach of the JVM suite. Duplicated configuration drifts quietly into
a wrong display; duplicated business logic drifts into a task that is on one calendar and not
the other, with no error anywhere. Same shape, worse consequence.

## Links

- `sync/GoogleSyncCoordinator.kt`, `presentation/CalendarSyncViewModel.kt`
- `2026-10-06-google-sync-failures-were-shaped-like-skips.md` — what closing items 1, 3 and 6 found
- `2026-10-05-google-sync-local-side-came-from-the-shadow.md`
- `2026-10-05-google-sync-decides-and-the-applier-writes.md`
- `2026-10-05-google-calendar-two-ports-and-local-wins.md`
