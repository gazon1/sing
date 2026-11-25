---
title: "The undo offer outlives the tap"
date: 2026-11-26
status: accepted
tags: [ui, snackbar, maestro]
issue: 3191
---

## Context

The delete flow of the task detail offers an undo in a snackbar. The snackbar
was shown with `SnackbarDuration.Short` — four seconds — exactly like a plain
notice that carries no action.

Four seconds is a reading time, not an answer time. The undo is the only way
back from a delete, and it is offered once: when it expires, the deletion
stands. Meanwhile the tap that presses it is not free. On the emulator used
for the qualification runs (API 36, 16-core host with several builds running
side by side) a single Maestro tap costs three to nine seconds, because the
element lookup and the wait for the app to settle are both slow on a loaded
host. The Maestro run of `tasks/06-delete-undo` measured a delete tap of
4.6 s, a wait for the snackbar of 1.0 s and a snackbar tap of 7.0 s — the
offer was gone before the tap could arrive, and the tap landed on whatever the
snackbar had been covering.

The same flow had passed three times in a row on 2026-11-24, when the taps on
the same build cost 2.1 s. The flow was never wrong; the window was too
small for the device it runs on.

## Idea

- **A.** Give the undo offer the duration Material 3 prescribes for a snackbar
  that carries an action: `SnackbarDuration.Long` (ten seconds).
- **B.** Keep four seconds and make the flow press the button faster (a
  coordinate tap instead of an id lookup).
- **C.** Keep four seconds and let the flow retry until the host is quiet
  enough.

## Decision

**A.** The undo offer is `SnackbarDuration.Long`; a plain notice stays
`SnackbarDuration.Short`.

## Rationale

- **The offer is an answer, not a notice.** Material 3 draws the line exactly
  where the product needs it: a snackbar with an action lives longer than one
  without. A four-second undo is the product telling the user "you have four
  seconds to change your mind" — which is not what the app means.
- **B optimises the measurement instead of the defect.** A coordinate tap is
  2.4 seconds faster than an id lookup (measured: 1.5 s against 3.8 s on the
  same screen) — but it is still faster than the window it has to fit into,
  and it buys the margin by hard-coding where the button sits on one screen
  size. The user gets the same four seconds either way.
- **C makes the suite's green depend on the machine.** The flow would pass
  when the host is idle and fail when it is busy, which is the opposite of what
  a qualification record is supposed to mean.
- **The defect is in the product, so the product is where the fix belongs.**
  The snackbar's lifetime was a product decision; a test cannot grant the user
  more time to undo a delete.

## Consequences

- The undo offer now lives ten seconds. The `Notification.Toast` branch is
  unchanged: a notice nobody has to answer still disappears on its own.
- `tasks/06-delete-undo` presses "Undo" and leaves through the top bar, not
  through `back`: the snackbar lives in a Popup — its own window — and that
  window still holds focus right after the tap, so a `back` press is spent on
  dismissing it and never reaches the editor underneath.
- The offer's lifetime is pinned by a guard test, so a later "make the
  snackbar quicker" change has to say out loud that it is making the undo
  unreachable again.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/NotificationHost.kt`
  — the undo branch and its duration
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/UndoOfferDurationTest.kt`
  — the guard test
- `Maestro/flows/tasks/06-delete-undo.yaml` — the flow that proves the undo
- `docs/decisions/2026-11-25-qualification-currency-is-the-flow-not-the-build.md`
  — why a qualification names the build that ran it
