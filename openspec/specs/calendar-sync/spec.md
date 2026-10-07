# calendar-sync Specification

## Purpose
Keep the app's tasks and the user's calendars agreeing, and make that agreement
legible. Two different features live here and the spec keeps them apart: a
one-way projection of tasks onto a *system* calendar, and a two-way peer with
*Google*. Each reports what it last did without borrowing the other's words, a
Google pass distinguishes "did not run" from "refused to run" from "tried and
failed", and a task that belongs on a calendar is decided by one definition
rather than by three that have to agree.

## Requirements

### Requirement: REQ-CS-001

The system-calendar projection and the Google sync SHALL be configured
independently, and neither SHALL report a status belonging to the other.

> The system projection writes into a device calendar provider under a permission
> the platform may refuse; Google is a two-way peer. One provider with capability
> flags would force one repository to answer "is sync on?" for two features that
> answer it differently.

#### Scenario: A Google pass does not move the system calendar's status
- A Google sync pass completes successfully
- The settings screen's Google half shows the pass's own timestamp
- The settings screen's System Calendar half shows its own, unchanged status

#### Scenario: The system projection is unavailable on this platform
- The platform cannot reach a calendar provider
- The System Calendar half reports that it is unsupported
- The Google half is unaffected and remains usable

#### Scenario: Permission is denied
- The user refuses the calendar permission
- The control offers to ask again rather than silently disabling itself
- The refusal is treated as transient, not as proof the platform is unsupported

---

### Requirement: REQ-CS-002

Whether a platform can project onto a system calendar SHALL be read directly by
the screen from the platform's permission requester, and SHALL NOT be carried as
a field on the settings state.

> `CalendarSyncUiState.isSupported` was written in one place and read in none. A
> flag nothing reads survives with a KDoc explaining why it mattered.

#### Scenario: A failing provider read settles the load
- Reading available calendars fails with an operational error
- The load finishes and carries whatever calendars it did gather
- The screen does not conclude the platform is unsupported

---

### Requirement: REQ-CS-003

Whether a task belongs on a calendar SHALL be decided by one definition —
"not completed and not trashed" — and every projection and every
change-detection pass SHALL use it.

> It was written out three times, in agreement by comment alone, and one copy lived
> in `androidMain` where the JVM suite could not have caught a divergence. A
> divergence shows up as a task on one calendar and not the other, no error anywhere.

#### Scenario: A completed task leaves both calendars
- A task is marked completed
- The next Google pass removes its event
- The next system pass removes its event

#### Scenario: A trashed task leaves both calendars
- A task is moved to trash
- Both projections stop carrying it on their next pass

#### Scenario: A task with a due date inside the window stays
- A task is due tomorrow
- Both projections keep it
- Neither treats a completed sibling's absence as a reason to drop it

---

### Requirement: REQ-CS-004

A Google sync pass SHALL distinguish three outcomes — it did not run, it declined
to run, and it tried and failed — and each SHALL reach the user differently.

> A boolean collapses "declined" and "failed" onto the one nobody retries: the
> worker reported a failure as "did not run" and answered with success, so nothing
> was retried and nothing appeared on screen.

#### Scenario: A failing pass is retried and reported
- A Google pass runs and the API returns an error
- The worker treats the pass as failed and schedules a retry with backoff
- The Google half of the settings screen shows the reason

#### Scenario: A declined pass is not retried
- The user has revoked access to the Google account
- The pass declines before any network call
- The screen reports that there is no account connected
- The success timestamp does not advance

#### Scenario: An anonymous session declines
- The app is not signed in
- No pass reaches the engine
- The screen reports that the user is not signed in

#### Scenario: Only a completed pass advances the clock
- A pass completes and the screen shows the time it ran
- A later pass fails
- The displayed time still reads the last pass that worked

---

### Requirement: REQ-CS-005

The Google half's "Sync now" control SHALL run a Google pass, and the system
half's control SHALL run the system projection. Neither SHALL drive the other.

These are two features behind one settings screen, and a live button on the wrong
one is a defect that looks like a feature. The intents are separate, and the
Google one reaches the Google coordinator rather than the system orchestrator.

#### Scenario: The Google control runs a Google pass
- The user presses "Sync now" in the Google half
- One Google pass runs
- No system-calendar projection runs

#### Scenario: The Google control is disabled during a pass
- A Google pass is running
- The control is disabled and reads as in progress
- The system-calendar control is unaffected

#### Scenario: A failure after the calendar list loaded is still visible
- The user has connected Google and chosen a calendar
- The next pass fails
- The screen shows the failure rather than appearing unchanged

---

### Requirement: REQ-CS-006

How far a Google listing reaches SHALL be chosen in exactly one place, and the
window the settings screen displays SHALL be the one the pass is configured with.

> Three sites once named it — a default argument on the engine, the same default
> argument on the event source, and a constant read directly in the screen — with no
> compiler error if they disagreed.

#### Scenario: A configured window is the displayed window
- The import window is configured to a non-default range
- The settings screen describes that range
- It does not describe the default

#### Scenario: Both listing and cleanup agree on the bounds
- A listing imports events inside the window
- Local cleanup deletes rows that age out of the same window
- No event is imported and then immediately discarded

---

### Requirement: REQ-CS-007

A recurring event's recurrence rule SHALL be stored and re-sent byte-identical to
the value Google supplied.

The rule is an opaque string to this feature. Re-deriving it from parsed dates
loses the cases Google's own syntax covers and this parser does not — weekly
interval counts, `BYDAY` with an ordinal, an `UNTIL` in UTC rather than local
time. A round trip that changes the bytes changes the series.

#### Scenario: A series round-trips unchanged
- Google returns an event with a recurrence rule
- The rule is stored and later re-sent on an update
- The re-sent string equals the stored string

#### Scenario: An edited series keeps its rule
- The user changes the time of a recurring event
- The series still occurs at its original recurrence
- The rule is unchanged by the time edit

---

### Requirement: REQ-CS-008

A remote change that moves a task's reminder SHALL re-arm the reminder's alarm
where the platform has one.

The applier updated the reminder row and stopped. The alarm that was already
scheduled for the old time remained scheduled, so a task moved to later fired at
the time it used to be due — the write succeeded, the row was correct, and the
notification still arrived at the wrong moment.

#### Scenario: A task moved later fires at its new time
- A remote change moves a task's due time later
- The pass re-arms the alarm for the new time
- The old alarm does not fire

#### Scenario: A task moved earlier fires at its new time
- A remote change moves a task's due time earlier
- The pass re-arms the alarm for the earlier time
- No stale later alarm remains

#### Scenario: A platform with no alarms is unaffected
- The platform has no reminder scheduler
- The reminder row is still written
- Nothing attempts to arm an alarm