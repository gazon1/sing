---
title: "An event bus has one consumer, so a screen collects its events once"
date: 2026-10-22
status: accepted
tags: [ui, tasks, mvi]
---

## Context

`EventBus` is a `Channel`, and `MviViewModel.events` exposes it as
`receiveAsFlow()`. A channel has exactly one consumer: two collectors of the
same flow split the events between them, and each collector keeps only the
ones it recognises. That is written down — `EventBus`'s own KDoc says the
buffered channel is "the correct default for ViewModels where a single
collector (typically `NotificationHost`) handles all events", and points
multi-subscriber cases at a shared flow instead.

`TaskDetailContent` had two collectors of `coordinator.events`:

```kotlin
LaunchedEffect(coordinator.events) { /* shows the "saved" message */ }
NotificationHost(events = coordinator.events, mapper = { /* undo toast, errors */ })
```

Which collector received the undo event was a race between the two. The task
detail's delete produced a `TaskDetailUiEvent.UndoDelete`, and if the screen's
own collector won the race, the event was dropped on the floor: the
`LaunchedEffect` ignores everything that is not `Saved`. The undo toast — the
only way back from a delete — was then never on the screen.

The measurement caught it: `Maestro/flows/tasks/06-delete-undo.yaml` waits for
`snackbar_action` after "Удалить" and the element was not on the screen. It
looked like a z-order problem, and it had been a z-order problem once before
(the toast used to be painted under the content and the FAB, which is why it
lives in a `Popup` now). The z-order was fine; the event never arrived.

## Idea

1. Make `EventBus` a `SharedFlow` so every collector sees every event.
2. Collect the events once in the screen and hand the mapped notifications to
   the host.
3. Leave the bus alone and document that a screen may only collect once.

## Decision

We did (2). The screen collects `coordinator.events` once and maps each event
to a `Notification`; `NotificationHost` gained an overload that takes an
already-mapped `Flow<Notification>`.

## Rationale

(1) changes the bus for every screen in the app, and "every collector sees
every event" is only safe while no screen collects the same events twice and
acts on them twice — a screen that shows a dialog in one collector and a
snackbar in another would then show both. That is a property to check across
the whole app, not one screen.

(2) fixes the defect where it happened, and it makes the ownership visible: a
screen that reacts to events in two places now has to say so in one place.

(3) is a rule nobody can enforce. The compiler cannot see two collectors of
the same flow, and a static check would have to model "the same flow" across
composables — which is exactly the kind of rule that decays.

## Consequences

- `NotificationHost` has two shapes: the generic one maps inside its own
  collector, the new one takes notifications that are already mapped. The
  generic one still collects the raw stream exactly once.
- A screen that needs to react to the same events in two places owns the
  collection and feeds the host — the task detail's own snackbar and the undo
  toast now come out of one `when`.
- The undo toast is addressable by UI automation again, which is what
  `tasks/06-delete-undo.yaml` asserts. Measured on the device after the fix:
  the flow finds `snackbar_action` and the undo restores the task.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/EventBus.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/NotificationHost.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskDetailContent.kt`
- `Maestro/flows/tasks/06-delete-undo.yaml`
