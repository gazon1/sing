---
title: "The Maestro harness dismisses the spotlight tour"
date: 2026-11-12
status: accepted
tags: ["ui", "maestro", "tests", "onboarding"]
---

# The Maestro harness dismisses the spotlight tour

## Context

`2026-11-09-the-desktop-harness-starts-after-the-spotlight-tour.md` fixed the
desktop harness, which mounted the app as a user who had never seen the
spotlight tour. The Maestro side had the same problem, and it showed up as
something that looked like a broken suite.

Every agenda flow on the physical device failed at its first assertion about the
agenda's content — "No visible element found", on rows that the flow had
already seen a moment earlier. The device was a realme RMX3938 (720x1600, API
35, Russian locale) on which the agenda screens were plainly visible to a human
looking at them.

The cause was the first-run spotlight tour. Its scrim (`SpotlightOverlay.kt`)
fills the screen and is clickable, so it swallows every tap under it, and the
agenda's own content is not in the accessibility tree while the tour is up. A
flow that taps a task row taps the scrim.

Two properties of the tour made this hard to see from the outside:

- It is a **first-run** modal, so it is armed on a device that has never seen
  it. On a device that has seen it, everything passes — which is why the same
  flows pass in CI's emulator and fail on a fresh phone.
- Its "seen" record is a **settings** record, not content. The runner wipes the
  content before every flow (`todo-debug://seed?reset=content`), and the wipe
  leaves the settings alone — so a device that once saw the tour keeps that
  state, and a device that has not seen it keeps seeing the tour on every
  single run. Neither state is reachable from the flow side.

The product behaviour is not a defect: a first-run tour is supposed to cover the
screen until the user finishes it.

## Idea

- Fix the test environment, not the product.
- Do not add a seed key for the tour: the seed vocabulary is the app's debug
  contract, and the tour's record is a settings record, not content.
- Dismiss it where the app is launched, so every flow inherits the fix.

## Decision

**A Maestro helper dismisses the tour, and `launch-clean.yaml` runs it.**

- `Maestro/helpers/skip-spotlight.yaml` runs one conditional step: when
  `onboarding_spotlight_card` is visible, tap `onboarding_spotlight_skip`. It
  then waits for the card to be gone (`extendedWaitUntil: notVisible`, 8 s — the
  budget the `dialog` kind carries, because the tour is a modal overlay; see
  `config/docs/ui-wait-budgets.txt`).
- `Maestro/helpers/launch-clean.yaml` runs that helper after the shell is up
  (`nav_tab_today` visible). Every flow that launches the app the clean way
  inherits the dismissal.
- The product's tour is untouched: it is still a modal first-run tour, and the
  "Skip" affordance is still what a user presses.

## Rationale

**The product is right and the environment was wrong.** Making the tour
non-modal, or letting taps through the scrim, would change a deliberate
first-run experience to satisfy a test environment — and would need its own
decision record. None of the flows under test is about the tour.

**The conditional is what makes it idempotent.** A device that has already seen
the tour has nothing to dismiss, and the helper's condition says so: the step
is skipped and the wait passes immediately. A device that has not seen it gets
one dismissal, and the record of that dismissal is what keeps the helper quiet
from then on. The helper needs no state of its own, and it cannot get the
device into a state a later flow cannot start from.

**The settings record is not the seed's business.** `todo-debug://seed?reset=content`
resets the content of the active profile. The tour's record is a settings
record, and adding a seed key for it would widen the app's debug contract for
a problem that the flow side can solve on its own.

**The dismissal belongs where the app is launched.** The tour is a first-run
thing: it is up exactly when the app starts for the first time. Every flow
starts the app, and `launch-clean.yaml` is where they do it, so the helper
belongs there rather than in each flow.

## Consequences

- The agenda flows can drive the agenda on a device that has never seen the
  tour. Measured on a realme RMX3938 (720x1600, API 35) on 2026-11-12: all ten
  agenda flows pass. Two of them still failed after the dismissal — the rename
  flow's caret and the date picker's day cell — but they failed on their own
  assertions, which is the point: the dismissal is what made them diagnosable
  at all instead of every flow dying on the scrim.
- The tour is no longer covered incidentally by the Android flows. It is
  covered by `SpotlightTourTest` and the spotlight unit tests, which bring
  their own onboarding record — the same arrangement the desktop side settled
  on.
- The helper is a *dismissal*, not a *reset*: it does not un-arm the tour. A
  flow that wants to assert on the tour itself has to bring its own device
  state, the way the tour's own tests do.
- The `Maestro/CONVENTIONS.md` helper table names the helper, so the next
  author reaches for it instead of writing another one.

## Links

- `Maestro/helpers/skip-spotlight.yaml`
- `Maestro/helpers/launch-clean.yaml`
- `Maestro/CONVENTIONS.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightOverlay.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourTest.kt`
- `docs/decisions/2026-11-09-the-desktop-harness-starts-after-the-spotlight-tour.md`
