---
title: The agenda's context menu is a gesture, not a decision — and it is the same on both platforms
date: 2026-10-21
adr-number: 2026-10-21-agenda-context-menu-parity
status: accepted
tags: [navigation, agenda, android, desktop, parity]
---

# The agenda's context menu is a gesture, not a decision — and it is the same on both platforms

## Context

The agenda shows a context menu on a task row. The menu is a *gesture*: Android opens
a bottom sheet on a long press, Desktop opens a popup on a right click. The *decision*
— which actions the menu offers — belongs to the platform too, because the two menus
are not the same menu: the Android sheet has no "share" row and opens a task as a row
of its own.

What should not have been a platform decision is **which sections show the menu at
all**. Android passed the menu host to five sections — inbox, today, upcoming,
project, tag — and to none of the three problem sections: blocked, stagnant,
overdue. Desktop passed it to all eight.

The two lists were written out separately, side by side, in two files that were
otherwise near-identical. Nothing said they were supposed to differ, and nothing
would have noticed if they did: the divergence was a three-line difference in a
file nobody compared against its twin.

## Idea

Treat the menu as a gesture — a platform's business — and treat the list of sections
that show it as one decision, made once.

## Decision

1. **The host is a parameter.** `AgendaNavGraph` takes an `AgendaContextMenuHost`:
   the platform renders the gesture, the graph decides where it applies.
2. **The list of sections is common.** All twelve agenda entries live in one
   commonMain file, and every task section — inbox, today, upcoming, project, tag,
   blocked, stagnant, overdue — gets the host. The saved-view sections (list,
   results, edit, create) list views, not tasks, so they get none.
3. **Android gains the three missing sections.** The Android host now reaches
   blocked, stagnant and overdue, matching Desktop.
4. **The list is held by a test.** `AgendaContextMenuParityTest` reads the common
   entry provider and asserts which sections pass the host.

## Rationale

- The gesture and the decision have different owners. A long press and a right
  click are the platform's; "a blocked task can be pinned and completed" is the
  app's. One file now says the second thing, on both platforms.
- The divergence was accidental, not a decision. Nobody wrote down "blocked tasks
  have no menu on Android" — the list simply stopped three entries short, and the
  two files were close enough that the gap looked intentional.
- Android is the side that gains. Desktop's list was the complete one, and it is
  the one a user reads: the same task, the same menu, whichever platform they are on.

## Consequences

- **Always** put a gesture behind a parameter and the *policy* behind the common
  code. A gesture that leaks into shared code becomes a second list to keep in sync.
- **Never** let two near-identical files hold two lists. The lists looked
  harmless next to each other; that is exactly why the difference survived.
- The parity test reads the source rather than rendering the menu. The property is
  "which sections pass the host", and that is a property of the code, not of a
  rendered screen.
- The Android menu still offers fewer actions than the Desktop one. That is a
  deliberate platform difference — a sheet and a popup are different surfaces —
  and it is not what this ADR equalises.

## Links

- ADR `2026-10-02-nav-entries-dedup-deferred` (addendum 2026-10-21) — the deduplication
  that put the agenda's entries in common code.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/nav/AgendaNavGraph.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/nav/AgendaContextMenuParityTest.kt`
