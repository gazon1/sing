# ADR compliance — decision, reality, deviation

Read alongside `current-architecture.md`. Each row: what was decided, whether the code does it,
where, and what differs. "Device" marks decisions I also checked on the phone.

The ADRs are from September 2026; several name files that no longer exist. That is expected after the
slot refactor (`2026-09-28`) and the merge of the nav graphs (`2026-10-05`), and it is *not* by itself
a defect — what matters is whether the **behaviour** survived.

---

## 1. Table

| ADR | Decision | Implemented | Evidence | Deviation |
|---|---|---|---|---|
| `2026-09-05-task-editor-refactor` | one editor screen + one VM with `mode`, dirty tracking, discard sheet, `TaskEditor(taskId)` route, named section files, pure `TasksFormatters` | **partial** | shared renderer `TaskEditorContent.kt:473`; create `:524`; route split into `TasksRoute.Create/Detail` (`TasksRoute.kt:37`) | route renamed; **no single `mode`/`dirty` state** — create and detail have separate VMs and separate draft types; `TasksFormatters.kt` absent; discard sheet exists only on create |
| `2026-09-07-task-detail-document-style` | hero + **meta chips** + checklist rows + **bottom action bar with badges** + `ActiveSheet` routing; delete dead `FieldMode` | **partial** | hero `TaskTitleRow.kt:136` + `TaskDescriptionField.kt:36`; sheet routing `TaskEditorSheet.kt` + `TaskEditorSheetsHost.kt:51`; checklist reuses `ChecklistItemRow` | **chips never happened** — attribute *cards* instead (`TaskAttributeCard`); **no bottom action bar** (`bottomBar = null`, `TaskDetailContent.kt:780`); `ActiveSheet` → `TaskEditorSheet`; draft *is* in the UI state (`titleDraft`, `TaskDetailUiState.kt:45`), contrary to the ADR |
| `2026-09-08-task-detail-critical-fixes` | kill TOCTOU; silent inline saves; remove dead conditions | **yes** | `TaskDraftSlot.kt:79-90` + `UpdateTask.kt:30-39`; entity setters get no `onSaved` (`TaskDetailCoordinator.kt:167-175`); no `\|\| true` in `feature/tasks` | mechanism changed (`_lastEditedAt` → `SavedPill`), contract held |
| `2026-09-09-task-detail-intent-refactor` | sealed intent + one dispatcher; routing in the composable; `_latestTask` as the only write base | **yes** | `TaskDetailIntent.kt:34`, `TaskDetailCoordinator.kt:503`, `sheets.show(...)` at 9 sites | `_latestTask` renamed `taskFlow`; slots are a stronger guarantee than the ADR asked for |
| `2026-09-15-task-detail-drafts-undo-fix` | seed drafts, expose `recentlyDeleted`, wire undo snackbar, `koinViewModel` | **partial / superseded** | `koinViewModel` `TaskDetailScreen.kt:25`; undo via events → `NotificationHost` (`TaskDetailContent.kt:155-275`) | `recentlyDeleted` gone, replaced by typed undo events; seeding is stricter (echo vs external rewrite) |
| `2026-09-28-task-detail-slot-refactor` | coordinator + 7 slots, marker interfaces, one merge | **yes** | slots under `presentation/viewmodel/slot/` | the merge now has **10** inputs, not 6 |
| `2026-10-05-one-task-detail-screen-after-a-merge-split-the-nav-graphs` | one `TaskDetailContent` for both platforms; restored section order | **yes** | `TasksNavGraph.kt:88`; sections at `TaskDetailContent.kt:525-778` | premise obsolete (one shared graph now) |
| `docs/checklists/detail-screen-dod.md` | inline silent edits; 4-icon bottom bar; sheets; 4-section anatomy; ≤500-line file; value-class callbacks; `CollectEvents`; tests per setter | **partial** | debounce+silent ✅; sheets ✅ | **no bottom bar**; 918-line screen; callbacks are a `data class`; raw `LaunchedEffect` on events; no TOCTOU test; no delete-confirm sheet |

---

## 2. The six deviations that matter for this research

### D1 — Meta chips became attribute cards, and then got a fill

The ADR's chips (`[📅 Today] [🚩 High] [📁 Work]`) were replaced by full-width rows
(`TaskAttributeCard`). Replacing chips with rows is defensible on a 360dp phone — chips are hard to
target. What is **not** defensible is the fill added later:
`containerColor = if (isActive) primaryContainer else surface` (`TaskAttributeCard.kt:43-44`).
Result: a filled row is a solid accent block with `onSurface` text at 3.67:1 (UXF-08), and the
accent changes with the user's theme seed.

### D2 — The bottom action bar was never built

The DoD still requires "4 icon buttons: Remind, Attach, Pin, Delete (or equivalent)" with badges.
Reality: no bottom bar; Archive/Delete in the overflow; Pin and Attach are rows; **Remind does not
exist at all**. The ADR's own goal — "replace `(none)` text blocks with badge counts" — is
unachieved; the `(none)` blocks became hint sentences instead (UXF-04).

### D3 — `ActiveSheet`/`TaskEditorSheet` routing exists, but three sheets bypass the host

`TaskEditorSheetsHost` wraps 8 of 14 variants in `TaskEditorSheetHost`. The date, time, start-date,
start-time, recurrence, dependencies and time-entry sheets bring their own chrome. So the "one
consistent sheet" goal is met for pickers and not for the scheduling family — which is exactly the
family that grew the `Clear/Cancel/OK` pattern (UXF-16).

### D4 — Reminders are wired end-to-end and drawn nowhere

`TaskEditorSheet` has no `Reminder`; no composable reads `TaskDetailUi.reminders`; `Domain.SetReminder`
has no dispatch site. The route KDoc still says the detail is the only place a reminder can be set.

**This is the clearest case of "the feature is implemented but not wired" in this repo** — the shape
`scripts/find-unwired-surfaces.py` exists to catch.

### D5 — Emoji were removed from the detail except one

The ADR deleted `📌`/`🗑`. One emoji survived:
`SubtasksSection.kt:66` → `Text("\uD83D\uDCCB")` (📋) as the Subtasks section icon, next to four
Material icons. Visible on device in `04-task-detail-bottom-empty.webp`.

*(A code search for the literal `📋` misses it because the source uses the escape `\uD83D\uDCCB` — it
is the only emoji in `feature/tasks` and it was found by reading the screen, not the grep.)*

### D6 — "Empty sections are invisible" was reversed into "say what is missing"

`2026-09-07` decided the sections disappear and the count moves to the bar. The code instead always
draws them with a hint, and there is a test asserting that
(`TaskDetailEmptyChildSectionsTest`: *"the task detail of a task with only a title says what its
empty sections are"*). Two defensible positions — but the decision was never re-recorded, so the ADR
and the code now say opposite things, and a reader cannot tell which one is current.

**Recommendation:** record the new rule as its own ADR when UXF-04 changes it, and link the old one.

---

## 3. DoD checklist, item by item (device-verified where marked)

| DoD item | Status | Note |
|---|---|---|
| title inline-editable | ✅ | 22sp, single line |
| description inline-editable | ✅ | |
| debounced (300 ms) | ✅ | `TaskContextDeps.debounceMs` |
| silent (no `Saved`) | ✅ device | no snackbar while typing |
| `_lastEditedAt` + "Saved X ago" | ⚠️ | `SavedPill` shows `Saved` while typing, then disappears; no resting relative time |
| `Saved` only for explicit actions | ✅ | `SavedPulse` for autosave |
| 4-icon bottom bar with badges | ❌ | not implemented |
| all pickers `ModalBottomSheet` | ✅ | `TaskEditorSheetHost` |
| shared host for chrome | ⚠️ | 8 of 14 variants |
| inline create in pickers | ⚠️ | tags yes (`AddTagDialog`), project no |
| Clear on date/time pickers | ✅ | |
| pre-select current value | ✅ | |
| destructive actions in a sheet, not a dialog | ❌ | delete is a menu item; no confirm at all (undo instead) |
| undo snackbar 4 s | ⚠️ | `SnackbarDuration.Short` |
| nav via chevron, not `combinedClickable` | ✅ | |
| every `IconButton` has `contentDescription` | ⚠️ | title checkbox ✅; **checklist row checkbox has no `content-desc`** (device dump) |
| screen file ≤ 500 lines | ❌ | 918 |
| value-class callbacks | ❌ | `data class TaskEditorCallbacks` |
| `CollectEvents` | ❌ | raw `LaunchedEffect` |
| previews with fixed timestamps | ⚠️ | previews exist in the section files |
| VM tests for every setter | ✅ | `TaskEntitySlotTest`, `TaskChildrenSlotTest`, … |
| TOCTOU race test | ❌ | none |
| formatter tests | ⚠️ | recurrence formatters tested; **date/time/priority formatters have no tests** |

---

## 4. What this means for the plan

The ADRs are **not** a list of things to redo. Five of the eight decisions are implemented and should
be preserved exactly (silent debounce, no TOCTOU, intent dispatch, slot decomposition, one shared
renderer). The work is concentrated in:

1. decisions that were **reversed without a new record** (D1, D6);
2. decisions that were **never built** (D2, D4);
3. decisions that were **built for the pickers only** (D3).

Each of those becomes one workstream in `implementation-plan.md`, and each needs its own ADR when it
lands — not a silent code change.
