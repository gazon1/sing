---
title: "The greeting follows the clock and the settings"
date: 2026-11-05
status: accepted
type: "decision"
supersedes: [2026-10-18-greeting-init-no-runblocking, 2026-10-18-greeting-computation-in-viewmodel-collector]
---

## Context

`AgendaViewModel` computed the agenda's time-of-day greeting once, in `init`, from the
configured greeting hours and the clock hour at that moment, and the value was snapshotted
into every `AgendaUiState.Loaded` the task collector produced. Its own comment said the
value "does not change during the lifetime of this ViewModel instance".

Two things followed, both wrong for the user:

- The greeting hours are user settings, held behind an observable repository. A user who
  set "good evening from 20:00" and returned to the agenda kept the greeting computed with
  the old hours until the screen — and its ViewModel — happened to be recreated (#1961).
- A screen left open across noon, or across the evening boundary, kept the greeting of the
  hour it was opened at.

There was a third defect, about *where* the value was drawn: the greeting was a second line
in the shared `TopAppBar` wrapper's `title` slot. On Android that wrapper draws no bar and
publishes the `title` slot only when a caller passes `overrideShell`, which the agenda never
did — so the shell drew the destination name and the greeting was dropped, while desktop
drew a bar and showed it. The two clients disagreed about a value neither tested (#2221).

## Decision

1. The greeting is derived, not assigned. `AgendaViewModel` holds
   `combine(morningEndHour, afternoonEndHour, localHourFlow(clock, zone)) { … }`, where the
   two settings are observed flows and `localHourFlow` (in `core/platform/Clock.kt`, the
   sibling of `todayFlow`) emits the current local hour and re-emits at every hour boundary.
2. The derivation is published into the *live* state, not only into a private flow: a
   collector writes `Loaded.copy(greeting = …)` through `updateState`, so a screen already
   on display re-renders. The copy touches the greeting and nothing else, which is what
   keeps the task list and the selection untouched.
3. A failed read degrades to the empty greeting and ends the observation — no greeting,
   never a stale one, never an exception escaping a collector.
4. The initial value is empty rather than a placeholder. A default greeting is a claim
   about the hour that is wrong until the first read lands.
5. The greeting is drawn in the agenda's screen *body*, above the list, tagged
   `TestTags.AGENDA_GREETING`; the bar keeps the destination title alone. The body is
   rendered by both clients, so the Android shell keeps naming the tab and the greeting
   stops being a desktop-only view.

## Rationale

The clock port (`kotlin.time.Clock`) has `now()` and no flow, so *something* has to re-read
it. Putting that loop beside `todayFlow` keeps hour arithmetic in one place and gives the
same test-friendly shape the repository already uses for time (`maxEmissions`, an injected
clock, an explicit zone). Re-deriving on a settings change comes free from `combine`.

Publishing into the state rather than into a private `StateFlow` alone is what the bug was
about: the screen renders `AgendaUiState.Loaded.greeting`, so a value that never reaches a
new `Loaded` is a value the user never sees.

## Consequences

- The greeting follows both inputs while the screen is on screen, without a recreate.
- Tests no longer count dispatches: they move a fake clock, advance virtual time past the
  hour boundary, and await the greeting. The four-`runCurrent()` choreography the one-shot
  computation needed is gone with it.
- A settings read that fails once leaves the greeting empty for the ViewModel's life
  instead of retrying. That is the accepted trade: an empty greeting is honest, a stale one
  is not, and a retry policy is a separate decision.
- DST makes a "next hour" longer or shorter than an hour; the flow re-reads the hour after
  every delay, so the value corrects itself at the next tick.
- The desktop bar no longer shows the greeting as a subtitle. That is the seam this record
  chose, in exchange for Android showing it at all.

## Links

- #1956, #1961, #2221
