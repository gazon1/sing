---
title: Phase 2 retro — ProfileSwitcher wiring, modal-drawer selector trap, pomodoro chip gap
date: 2026-10-01
status: accepted
tags: [test-coverage, desktop, maestro]
description: Phase 2 retro — ProfileSwitcher wiring fix, NO-MARKER tagging, and pomodoro chip-flow desktop gap
---

# Phase 2 retro — desktop coverage ratchet

## What was done

Items 1–6 from the Phase 2 plan:

| # | Item | Status |
|---|------|--------|
| 1 | ProfileSwitcher in `menuEntries` + profile flow mirror | ✅ Done |
| 2 | NegativeFlowTest mirrors (6 flows) | ✅ Already complete (prior worktree) |
| 3 | LifecycleFlowTest stubs (2 flows) | ✅ Already complete (prior worktree) |
| 4 | Phase 2 retro ADR | ✅ This document |
| 5 | NO-MARKER flows: add smoke/regression tags (7 flows) | ✅ Done |
| 6 | PagerFlow pomodoro mirrors (3 flows) | ⚠️ Partial — 2/5, 3 unmirrorable |

Coverage: **14/49 → 15/49** (gained 1 from `ProfileFlowTest.create_profile_from_drawer`).

---

## FIX — ProfileSwitcher unreachable on desktop (Phase 1 finding closed)

**Root cause (Phase 1):** `AppDestination.ProfileSwitcher` existed in
`AppDestination.kt` but `DestinationKind.menuEntries` and `DesktopShell.MENU_ENTRIES`
did not include it.

**Fix applied:**
- Added `AppDestination.ProfileSwitcher` to `DestinationKind.menuEntries` in
  `AppDestination.kt`.
- Added `"Profiles"` to `DesktopShell.MENU_ENTRIES` in `DesktopNavigation.kt`.

**ProfileFlowTest** was written as the desktop mirror of
`Maestro/flows/profile/01-create-profile.yaml`. It navigates to Profiles via the
drawer, opens the create dialog, fills in a name, and confirms — verifying the new
profile item appears in the list.

**Lesson:** Hardcoded nav-constants that mirror production `enum`/`list` values are
a durable source of drift. A future phase should consider generating
`DesktopShell.MENU_ENTRIES` from `DestinationKind.menuEntries` at compile time
(e.g. a `expect val` in commonMain resolved by each platform's actual).

---

## FIX — ProfileSwitcherScreen missing testTags

**Finding:** `CreateProfileDialog`'s `OutlinedTextField` (name input) and
`TextButton` (Confirm) had no `testTag`. The Phase 1 ProfileFlowTest used
`onNodeWithText("Profiles")` which matched 4 nodes (drawer entry, top-bar title,
content heading, FAB area) due to the modal drawer rendering both layers
simultaneously.

**Fix applied to `ProfileSwitcherScreen.kt`:**
```kotlin
// Name input
OutlinedTextField(
    modifier = Modifier.testTag(TestTags.PROFILE_CREATE_NAME_INPUT),
    ...
)
// Confirm button
TextButton(
    modifier = Modifier.testTag(TestTags.Dialog.CONFIRM),
    ...
)
```

New constant added to `TestTags.kt`:
```kotlin
const val PROFILE_CREATE_NAME_INPUT = "profile_create_name_input"
```

`TestTagsCatalogJvmTest` caught this gap at build time — the missing constant was
not in `Maestro/TAGS.md`. Running with `-PupdateGoldens=true` regenerated the
catalog.

**Lesson:** `TestTagsCatalogJvmTest`'s reverse-wiring check (grep-ad-hoc-string)
and golden-file check form a complete safety net. Every new UI element with a
`testTag` modifier must add a matching constant to `TestTags`; the catalog test
enforces this bidirectionally.

---

## FIX — ModalNavigationDrawer selector trap

**Finding:** `ModalNavigationDrawer` on desktop renders **both** the drawer side
sheet AND the main content simultaneously. The drawer sits at `x=-1024` but is
still in the semantics tree. Any `onNodeWithText("...")` for a label that appears
in both the drawer entry and the destination screen (e.g. "Profiles", "Settings")
matches all copies — typically 3–4 nodes.

**Pattern established:** Prefer `testTag` over `onNodeWithText()` for any element
that appears in both the drawer and the destination it navigates to. The drawer
entries have no `testTag` (only `contentDescription`), but screen-level elements
should always carry one.

**Lesson:** `DesktopShell.isDrawerOpen()` uses bounds checking to detect when the
drawer is actually on-screen; this pattern (semantic presence ≠ visual presence)
appears in several places and should be documented in the test-helpers KDoc.

---

## KNOWN GAP — Pomodoro chip flows unmirrorable on desktop (deferred-backlog)

Three Maestro flows depend on selecting a task chip before operating the timer:

| Flow | What it tests | Desktop blocker |
|------|---------------|----------------|
| `pomodoro/02-start-focus-task.yaml` | tap chip → timer starts | `JvmPomodoroTaskListProvider` returns empty list |
| `pomodoro/04-skip-to-break.yaml` | skip advances Work → ShortBreak | same |
| `pomodoro/05-stop-resets.yaml` | stop resets to paused state | same |

**Root cause:** `JvmPomodoroTaskListProvider.tasks()` returns `MutableStateFlow(emptyList())`.
Desktop has no "inbox" concept, and the binding cannot be overridden from a flow test
without replacing the platform module — out of scope for the flow suite.

**What is covered:** `PomodoroFlowTest` covers play/pause/skip/stop controls from
the initial paused state without needing a chip fixture. The timer state machine is
fully exercised; only the "assign focus task" path is unreachable on desktop.

**To fix:** Override `PomodoroTaskListProvider` binding in the test harness to
return a seeded task list, then the three unmirrorable flows become mirrorable.
This requires either:
- (a) A `FakePomodoroTaskListProvider` injectable via Koin test module, or
- (b) Extracting the binding to an `expect/actual` that tests can replace.

**Action:** Add to `deferred-backlog.md` — see `pomodoro-chip-provider-override`.

---

## DONE — NO-MARKER flows tagged

All 7 flows now carry a quality tag:

| Flow | Added tag |
|------|-----------|
| `backup/01-round-trip.yaml` | `smoke` |
| `profile/02-isolation.yaml` | `smoke` |
| `system/search-finds-task.yaml` | `smoke` |
| `tasks/06-delete-undo.yaml` | `smoke` |
| `tasks/07-cyrillic-title.yaml` | `smoke` |
| `tasks/08-date-buckets.yaml` | `smoke` |
| `tasks/09-rename-empty.yaml` | `smoke` |

---

## Observations for future phases

1. **ProfileSwitcherScreen lacks `TestTag` on all interactive elements.** Beyond the
   name input and Confirm button fixed here, the full screen should be audited for
   missing testTags (profile item rows, emoji/color pickers, delete button).
   Tracking: `deferred-backlog.md#profile-switcher-screen-test-tags`.

2. **JvmPomodoroTimer is a placeholder.** It runs but does not fire real ticks.
   The existing `PomodoroFlowTest` exercises the UI state machine correctly, but
   a future phase using a `FakePomodoroTimer` that advances virtual time would
   enable timer-expiry tests.

3. **Coverage audit script location.** `scripts/maestro-coverage-audit.py` lives
   at the repo root, not inside `Maestro/`. The Phase 6 plan (CI integration)
   should account for this path.

4. **Duplicate nav-constants risk is structural.** `DesktopShell.TABS`,
   `DesktopShell.MENU_ENTRIES`, and `DestinationKind.tabs` /
   `DestinationKind.menuEntries` are four independent declaration sites for the
   same logical set. A future refactor should unify them, ideally via the
   navigation graph itself.

---

## Phase 2 acceptance criteria

- [x] ProfileSwitcher wired to desktop drawer and `menuEntries`
- [x] `ProfileFlowTest` written and passing
- [x] `TestTagsCatalogJvmTest` green (constant wired to TAGS.md)
- [x] 7 NO-MARKER flows tagged with `smoke`
- [x] PomodoroFlowTest: 2/5 flows covered; 3 documented as deferred
- [x] Full `shared:jvmTest` + `desktopApp:test` suite green
