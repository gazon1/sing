# Test coverage — what exists, what is missing

Two questions this file answers: *what already guards the behaviour I am about to change* and *what
must I write first*. "No test found" means exactly that — it was searched, not assumed.

---

## 1. What exists today (by tier)

### ViewModel / slot unit tests — the strongest tier

| File | Covers |
|---|---|
| `commonTest/.../viewmodel/TaskDetailCoordinatorTest.kt` | state assembly, error path, intent routing, proposal application/dismissal, every-variant-routed |
| `.../viewmodel/slot/TaskDraftSlotTest.kt` | seed, debounce persists after 300 ms, **no feedback loop**, **exactly one `SavedPulse`**, blank title refused, untouched fields preserved, no replay |
| `.../viewmodel/TaskDetailDraftStateTest.kt` | seed vs echo vs external rewrite, revision counting, `isDirty` |
| `.../viewmodel/slot/TaskEntitySlotTest.kt` | priority, pin, **clear due date writes null**, tag removal + undo, project resolution |
| `.../viewmodel/slot/TaskChildrenSlotTest.kt` | checklist add/toggle/delete + undo, subtask parenting, blocked-completion guard |
| `.../viewmodel/slot/TaskCompletionSlotTest.kt` | completion, recurring completion, prerequisite guard |
| `.../viewmodel/slot/TaskRemindersSlotTest.kt` | reminder write, saved-view capture, AT_DUE clears, platform gate |
| `.../viewmodel/slot/TaskLifecycleSlotTest.kt` | delete/archive + undo, series delete/restore, restore failure keeps the snapshot |
| `.../viewmodel/slot/TaskSeriesScopeTest.kt` | series-scope writes vs occurrence writes |
| `commonTest/.../tasks/TaskCreateViewModelTest.kt` | create validation, dates, multi-date series, parallel save guard, discard |
| `jvmTest/.../tasks/TaskCreateDebounceTest.kt` | create draft autosave |
| `jvmTest/.../tasks/TaskLifecycleIntegrationTest.kt` | checklist repository round-trip |

**Consequence:** the *state machine* of the detail is well covered. Any redesign that keeps intents
and slots keeps these tests for free — that is the cheapest way to ship UXF-04/07/18 safely.

### Compose UI tests — Desktop

`desktopApp/src/jvmTest/.../flows/tasks/` — 20+ classes: section order
(`TaskEditorSlotsOrderTest`), first-run offer (`TaskDetailFirstRunOfferTest`), empty sections
(`TaskDetailEmptyChildSectionsTest`), 360×800 fold (`TaskDetailFoldFlowTest`), priority and due-date
flows, recurrence scenario, tags, item independence, workflow state, sheet reachability, create flow,
time tracking.

### Android instrumentation

`EditorBackGuardTest` (create only), `TaskEditorDraftTest`, `CreateTaskFlowTest`,
`NavigationSmokeTest`, `MenuSheetBoundsTest`. **No instrumentation test for the detail screen.**

### Maestro

`flows/tasks/` — open, priority, due date + cancel, delete, archive via menu, **delete + undo**,
cyrillic title, date buckets, rename-empty, swipe delete + undo, create full task, prefill variants,
daily recurrence, time tracking, checklist (`TASK-CHECK-01`), subtasks (`TASK-SUB-01`), logbook,
toggle complete, rotation, deep link. Attachments: 5 flows.

### Screenshot / snapshot

**None.** Roborazzi was planned and dropped
(`docs/decisions/2026-09-30-roborazzi-not-built-superseded.md`). There is no baseline for any screen.

---

## 2. Gaps that this work will hit

Ordered by which workstream touches them.

| # | Behaviour | Existing | Gap | Test to add |
|---|---|---|---|---|
| G1 | **`flushDraft()` on Back** — the only thing between a keystroke and a lost title | none (`flushDraft` has two production call sites, zero tests) | **write before touching the screen** | slot test: type, call `flush()`, assert the repository has the new title; and Compose/instrumentation: type, press Back, assert persisted |
| G2 | IME never hides the focused field | none | new | Android instrumentation: assert focused bounds ∩ IME inset = ∅ |
| G3 | Recurrence renders a sentence | `RecurrenceFormattersTest` covers the picker's strings | the detail row had no test | formatter test for every unit/termination + Compose test that the row contains no `Interval(` |
| G4 | Reminder row exists and pre-selects | `TaskRemindersSlotTest` (state) | no UI test — there is no UI | Compose test for the row and the sheet's pre-selection; Maestro flow |
| G5 | Deadline row shows/clears | none (the field is unrendered) | new | slot test (write/clear) + Compose test |
| G6 | `someday` surfaces | none | new | slot + Compose |
| G7 | Contrast ≥ 4.5:1 across 8 accents × light/dark | none | new | theme unit test computing ratios |
| G8 | Muted token AA | none | new | same test |
| G9 | Row height / rhythm | `TaskDetailFoldFlowTest` (fold position only) | no height assertion | Compose test measuring a row = 48dp |
| G10 | Title wraps to 3 lines | none | new | Compose test with a 90-char title |
| G11 | Bar shows the task title when the hero leaves | none | new | Compose test scrolling past the hero |
| G12 | Completed task looks completed | none | new | Compose test: strikethrough + replaced date row |
| G13 | Empty sections follow the new rule | `TaskDetailEmptyChildSectionsTest` asserts the **current** rule | must be rewritten, not deleted | rewrite with the new expectations |
| G14 | `Ещё` sheet lists exactly what is hidden | none | new | Compose test comparing the sheet's items with the absent rows |
| G15 | Checklist add row is below the items | none | new | Compose test on the order |
| G16 | Checklist progress fraction | none | new | Compose test reading the progress node |
| G17 | Checklist collapse threshold | none | new | Compose test with 26 items |
| G18 | Checklist reorder persists | none (no reorder exists) | new | slot test for the new intent |
| G19 | Checklist delete + undo restores the row index | `TaskChildrenSlotTest` covers the delete+undo of the item | the *index* is covered; the UI path is not | Compose test for swipe → snackbar → restored |
| G20 | Clear-date via the row ✕ | `TaskEntitySlotTest:208` (unit only) | no UI test | Compose test tapping the ✕ |
| G21 | Create has the same fields in the same order | `CreateTaskFlowTest` (desktop) covers some | no parity assertion | Compose test comparing the ordered field tags of create vs detail |
| G22 | No error on a pristine create form | `TaskCreateViewModelTest` covers validation | the "pristine" case is not asserted | Compose test: no `Title is required` on open |
| G23 | Save returns to the new task | none | new | Compose test asserting the destination after save |
| G24 | Overflow opens on one tap | none | new | Compose test: tap once, one menu |
| G25 | Project picker tree/emoji/search | `ProjectPickerDismissTest` only | the `2862-…` list is untested on our side | the tests named in that document |
| G26 | One language on screen | none | new | arch test scanning for user-visible literals |
| G27 | No emoji (literal **or** `\uXXXX`) in `feature/tasks` | none — and a plain grep misses `\uD83D\uDCCB` | new | arch test scanning source text for both forms |
| G28 | Screenshot baselines | none | new tier | Roborazzi (see `implementation-plan.md` §3) |
| G29 | TOCTOU under a concurrent remote edit | **no test found** (a DoD requirement) | new | slot test: debounce an edit, apply an external write during the window, assert both survive per `TaskDetailDraftState`'s rules |
| G30 | Desktop time-tracking cell | `TaskDetailTimeTrackingSectionTest:94` refers to `TaskTimeCarrierTest`, **which does not exist** | dangling reference | either write it or fix the comment |

---

## 3. Tests that will break and must be updated, not deleted

| Test | Why it breaks | What it should assert after |
|---|---|---|
| `TaskDetailEmptyChildSectionsTest` | asserts the hints of six empty sections | the *new* empty rule: one-line headers, no hint sentences, `Ещё` present |
| `TaskDetailFoldFlowTest` | asserts due date + priority above the checklist at 360×800 | still true, with the new 48dp rhythm; add "first checklist row visible" |
| `TaskEditorSlotsOrderTest` | asserts banner/rows/children order | unchanged contract — keep |
| `TaskDetailOfferSlotTest` | asserts the offer card's position | `Quick actions` → normal section card (UXF-22) |
| `TaskEditorProjectRowTest` | project row rendering | unchanged — keep |
| `TaskDetailFirstRunOfferTest` | `Ask AI` chip in the bar | update if the card is restyled |
| `Maestro/flows/tasks/02-set-due-date.yaml`, `03-due-date-cancel.yaml` | date sheet chrome changes (WS6) | new sheet buttons |
| `Maestro/flows/tasks/05-archive-via-menu.yaml`, `06-delete-undo.yaml` | menu behaviour (WS9) | one-tap menu |

---

## 4. Seeds for UI tests

The Compose and Maestro tiers need tasks that exercise each state. Reuse the fixtures from
`test-data.md` rather than inventing new ones:

| Seed | State |
|---|---|
| title only | empty task (§1 of the wireframes) |
| date + project + priority | normal task |
| date + time + reminder + repeat + deadline + project + tags + state + checklist + subtasks + attachments + time + logbook | fully filled |
| 26 checklist items | long task / collapse |
| very long title (use the real `Отмамамыларамливаю книгу head first`) | title wrapping |
| completed task | completion styling |
| task in trash | read-only |

For Android screenshots: fixed clock, Blue accent, light theme, `fontSizeScale = 1.0`, 360×800.
