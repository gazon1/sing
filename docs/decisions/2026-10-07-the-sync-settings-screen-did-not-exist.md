---
title: The sync settings screen did not exist, and neither inert-surface gate could see it
date: 2026-10-07
status: accepted
tags: [sync, di, gates, defect-class]
---

# The sync settings screen did not exist, and neither inert-surface gate could see it

## Context

PR-6 asks for a "sync attachments" row next to the existing sync settings. Going looking
for those settings turned up something the plan had assumed rather than checked.

- `SyncViewModel` is bound in the Koin graph (`CoreDiModule.kt:364`) and **never injected
  anywhere**. Its only references outside that binding are its own file and its test.
- `SyncButton` has **zero call sites** outside its own file.
- `SettingsTab` has no `Sync` entry. The tabs are Interface, Agenda, Notifications,
  AI Provider, Work Schedule, Calendar, Tags, Tag Groups, Files, Backup, Account.

So the sync engine, its runner, its state repository, its preferences, its view model and
its status button are all present, tested and connected — and there is no screen a user
can reach any of it through. The settings section PR-6 wanted to extend did not exist.

## Why neither gate caught it

`find-unwired-surfaces.py` looks at composables whose names end in `Tile`, `Row` or `Dialog`
and at DI bindings that nothing resolves. `SyncButton` matches none of those suffixes, and
`SyncViewModel` is a class, not a composable — a bound-but-never-injected ViewModel is not
in the script's model of an unwired surface. Both artefacts are real, and neither is the
kind of thing that script was written to look for.

This is worth stating plainly because it is the *second* time in this change set that the
defect was found by reading rather than by a gate. The gates found 18 inert controls and
three unbound repositories; they did not find a missing screen.

## Idea

1. Add the attachment row to a screen that does not exist yet. Rejected: PR-6's UI
   requirement still has to land somewhere real, and inventing a second settings surface
   next to a dead one would be the same defect at a new address.
2. Build the sync settings screen. It is the missing end of a path whose other end is
   already written, tested and bound.
3. Delete `SyncViewModel` and `SyncButton` as dead code. Rejected: the sync core is 37
   files of working, tested engine. Deleting the two things that would surface it would
   remove the capability rather than deliver it, and the plan is explicit that the rule
   for this situation is to decide between "dead code" and "unfinished feature" first —
   and here the engine is neither dead nor a stub.

## Decision

Build the screen. `SettingsTab.Sync` renders `SyncViewModel`'s state and hosts the
attachment row, which makes both artefacts reachable at the same time.

The screen is deliberately scoped to what the view model already exposes: status, sync
now, auto-sync, interval, connection test. It adds no new sync capability, and it does not
touch the sync engine.

## Rationale

The interval control cycles through a fixed list rather than opening a picker dialog. Five
choices do not justify a screen and a focus trap to save two taps, and the row's subtitle
states the list. A value the current build does not recognise starts the cycle at the first
choice instead of wrapping to the end.

`SettingsValueRow` and `SettingsActionRow` gained a `testTag` parameter with a shared
default, so the new screen's rows are addressable in UI tests without every row needing a
literal.

The nav rail's comment said "eleven ~76dp rows" and said the scroll exists because that is
taller than a phone viewport. Adding a tab made the count wrong, so the comment was updated
rather than left describing a number that no longer held. The rail already scrolled on every
form factor; nothing about the scrolling behaviour changed.

## Consequences

- `SettingsTab` has twelve entries. Anyone reading the old comment to reason about the
  rail's height will now be off by one row, which is why the comment was corrected here.
- `SyncViewModel` becomes reachable for the first time. It has a test suite that was
  written without a UI, so some of its behaviour has only ever been exercised by injected
  tests. That is a reason to look at it, not a reason to distrust the screen — but the UI
  itself has not been run.
- `find-unwired-surfaces.py` still cannot see this class of defect. Adding "Button" to
  `_COMPOSABLE_SUFFIXES` would need a sweep to avoid a wall of false positives; that is
  follow-up work, not something to smuggle into this change.

## Links

- Plan: `attachment-sync-setting` (OpenSpec change).
- Related: `2026-10-07-attachment-sync-is-staged-behind-a-server-blocker`, which is the
  reason the row this screen hosts is locked rather than live.
