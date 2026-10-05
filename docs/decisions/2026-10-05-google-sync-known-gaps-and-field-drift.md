---
date: 2026-10-05
slug: google-sync-known-gaps-and-field-drift
status: proposed
---

# Known gaps in Google sync, and the two ways this feature drifts

Everything here is **not done**. It is written down because each item is invisible from the
code, and each one has a shape that has already produced a defect somewhere in this feature.

## Open items

### 1. A Google sync pass writes no status the user can see

`GoogleSyncCoordinator` deliberately does not call
`CalendarSyncRepository.setStatus`, because that backs the *system* calendar's status and the
settings screen renders it under the system half. Recording a Google pass there would show
"Last synced 14:02" under System Calendar while the Google half said nothing — a confidently
wrong answer in the one place the user has learned to trust.

The consequence is that `googleError` currently covers **only** `listCalendars()`. A sync that
fails after the calendar list loaded is invisible: the "Sync Now" button does nothing and says
nothing, which is the same defect as the desktop permission stub fixed earlier in this branch.

**Wants:** a `GoogleSyncStatus` on `GoogleCalendarSettingsRepository`, with its own codec
alongside `CalendarSyncStatusCodec`, rendered in the Google half. Not invented here because a
field with no screen behind it is the defect, not the fix.

### 2. The import window is displayed from a constant, not from configuration

`CalendarSyncSettingsScreen` renders `ImportWindow.DEFAULT` directly, while
`GoogleSyncEngine` and `GoogleCalendarEventSource` each default their `importWindow` parameter
to the same constant independently. Nothing overrides it, so today the constant genuinely *is*
the value — but there are now three places to change and one place to remember.

This was a live example of the "dead state field" problem below: the field that *did* exist on
`CalendarSyncUiState` (`importWindow`) was never assigned, so it always displayed
`ImportWindow.DEFAULT` while looking configurable.

**Wants:** one source. When the window becomes user-editable, it moves to
`GoogleCalendarSettingsRepository` and the screen reads it from state.

### 3. `GoogleSyncCoordinator` has no tests

It holds the guard that matters most and is asserted by nothing:

```kotlin
if (userId == UserId.anonymous) return Outcome.skipped("not signed in")
```

`UserId.anonymous` is a value a signed-out session genuinely holds, and every id-bearing
store in this feature is keyed by user. Without that check a signed-out app would sync
whatever credential sits under the anonymous profile's key.

It is untestable as written because `GoogleSyncCoordinator` takes
`engineProvider: () -> GoogleSyncEngine` — a concrete class with four DAO collaborators. There
is no seam to fake.

**Wants:** extract `interface GoogleSyncPass { suspend fun sync(calendarId): PassResult }`,
have `GoogleSyncEngine` implement it, and inject the interface. A five-line port that makes
the anonymous guard testable is worth more than the concrete type it replaces.

### 4. Two dead state fields were removed rather than wired

`CalendarSyncUiState.systemCalendarSupported` and `.importWindow` were declared, read by the
screen, and **never assigned**. They were deleted (see item 2 for what replaced the second).

The gate `find-unwired-surfaces.py` did not catch them because it looks for classes and
functions with no call site, not for properties that are read but never written. That gap is
worth closing in the script: a `val` on a UI-state class with no assignment anywhere is the
same class of defect as an unwired screen, and both are "looks like it works, does nothing".

### 5. `desiredEventFor` reads one task per event per pass

The push walk now does N single-row reads on every pass, where `desiredEvents()` does one
`observeAll().first()` for the insert case. At calendar scale (hundreds) this is fine. It is
recorded because the asymmetry will look like an oversight to the next reader, and the honest
reason for it — the pull walk needs the *current* task for a merge, while the insert walk only
needs the task set — is easy to lose.

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

## Links

- `sync/GoogleSyncCoordinator.kt`, `presentation/CalendarSyncViewModel.kt`
- `2026-10-05-google-sync-local-side-came-from-the-shadow.md`
- `2026-10-05-google-sync-decides-and-the-applier-writes.md`
- `2026-10-05-google-calendar-two-ports-and-local-wins.md`