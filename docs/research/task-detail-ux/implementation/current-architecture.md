# Current architecture — what the code actually is

Verified against `gazon1/bichir` on 2026-11-12. Every path below exists; line counts are from
`wc -l`. Where a name people use ("TaskDetailViewModel", "TaskEditorScreen") **does not exist**, that
is stated, because two of the ADRs and the DoD still use those names.

---

## 1. Names that do not exist (stop looking for them)

| Common name | Reality |
|---|---|
| `TaskDetailViewModel.kt` | **absent** — the view model is `TaskDetailCoordinator.kt` (652 lines) |
| `TaskEditorScreen.kt` | **absent** — the editor is a composable, not a screen |
| `TaskEditorViewModel.kt` | **absent** — create has `TaskCreateViewModel`, edit has `TaskDetailCoordinator` |
| `TaskDetailUi.kt` | **absent as a file** — `TaskDetailUi` lives in `presentation/state/TaskDetailUiState.kt` |
| `ActiveSheet.kt` (tasks) | **absent** — tasks use `TaskEditorSheet`; `ActiveSheet` is a *projects* type |
| `BottomActionBar` | **absent** — only `ProjectBottomActionBar` exists (projects) |
| `IconButtonWithBadge` | **absent** repo-wide |
| `SectionHeader` (shared) | **absent** — two private copies in search/menu; the detail uses `ExtraSectionCard` |
| `ReminderPickerSheetContent` | **absent** — `ReminderPickerSheet` exists for *projects* only and is unreachable |
| `TasksFormatters.kt` | **absent** — `priorityLabel` is private in `TaskEditorContent.kt:645` (dead code) |
| `FieldMode` | **type exists, unused** — `core/ui/components/FieldMode.kt`, kept alive only by its own test |

---

## 2. Layer map

```
TasksRoute.Detail(taskId, sourceViewId)                feature/nav/TasksRoute.kt:37
  └─ TaskDetailScreen(route)                           presentation/screen/TaskDetailScreen.kt (32)
       ├─ koinViewModel { parametersOf(route) }        → TaskDetailCoordinator
       └─ TaskDetailContent(coordinator, now)          presentation/screen/TaskDetailContent.kt (918)
            ├─ Scaffold(snackbarHost, NO topBar, NO bottomBar)   :277
            ├─ TaskDetailTopBar(...)                   components/detail/TaskDetailTopBar.kt (131)
            └─ TaskEditorContent(...)                  components/detail/TaskEditorContent.kt (737)
                 ├─ TaskTitleRow                        components/detail/TaskTitleRow.kt (197)
                 ├─ TaskDescriptionField                components/detail/TaskDescriptionField.kt
                 ├─ bannerSections  (slot)              → FirstRunSection, TaskDetailProposalSection
                 ├─ state / priority / estimate / due / project / tags / recurrence / pin
                 ├─ extraSections   (slot)              → Related, Tags, Recurrence, Checklist,
                 │                                        Subtasks, Attachments, Backlinks, Series,
                 │                                        Time, Logbook
                 └─ TaskEditorSheetsHost                components/detail/TaskEditorSheetsHost.kt (352)
```

`TaskCreateScreen` (`presentation/screen/TaskCreateScreen.kt`, 311) renders the **same**
`TaskEditorContent` through `TaskCreateEditorContent` (`TaskEditorContent.kt:524`), with its own
`TaskCreateViewModel`.

So: **one renderer, two view models, two state shapes, two save mechanisms.**

---

## 3. Render order (the contract, written twice)

`TaskEditorContent.kt:69-125` documents the order; `TaskDetailContent.kt:106-122` repeats it. On
device it renders as:

| # | Row | Detail | Create |
|---|---|---|---|
| 1 | Title + checkbox | ✅ | ✅ |
| 2 | Description | ✅ | ✅ |
| 3 | banner slot (`Quick actions`, proposals) | only first-run / proposals | — |
| 4 | Checklist **card row** | ❌ (`TaskDetailContent.kt:498` `checklist = null`) | ✅ |
| 5 | Attachments **card row** | ❌ (`:797` `showAttachmentsRow = false`) | ✅ |
| 6 | Workflow state | ✅ | ❌ |
| 7 | Priority | ✅ | ✅ |
| 8 | Estimate | ✅ | ❌ |
| 9 | Due date | ✅ | ✅ |
| 10 | Start date | ❌ (`:437`) | ✅ |
| 11 | Project | ✅ | ✅ (appears after the title is non-empty) |
| 12 | Tags | ✅ | ❌ |
| 13 | Recurrence | ✅ | ❌ |
| 14 | Pin | ✅ | ❌ |
| 15 | extra slot: Related, Tags-section, Recurrence-section, Checklist, Subtasks, Attachments, Backlinks, Series, Time, Logbook | ✅ | ❌ |
| 16 | `TaskSaveBar` | ❌ (`bottomBar = null`, `:780`) | ✅ |

Two rows are deliberately suppressed on the detail *because* the item lists live in the extra slot —
so a checklist is drawn by two different composables depending on the screen
(`TaskDetailChecklistSection` vs `ChecklistEditorSheet`), and each has its own add/delete path.

---

## 4. State

`TaskDetailUiState` is a sealed hierarchy, not a flat data class:

```kotlin
sealed interface TaskDetailUiState {
    data object Loading
    data class Error(val message: String)
    data class Loaded(val ui: TaskDetailUi, val extras: TaskDetailExtras, val pendingBlockedClose: Any?) 
}
```

`TaskDetailUi` (`TaskDetailUiState.kt:35-95`) carries `task`, `titleDraft: Revisioned<String>`,
`descriptionDraft: Revisioned<String>`, `project`, `tags`, `checklist`, **`reminders`**,
`subtasks`, `blockedBy`, `followsUp`, `relatedTasks`, `linkedNotes`, `linkedTasks`,
`logbookEntries`, `timeSlotState`, `seriesMembers`, `firstRun`.

**`reminders` is written and never read** — no composable takes it (see UXF-02).

Assembled by a `combineStates` over ten inputs in `TaskDetailCoordinator.init` (L349-416); the
coordinator delegates to seven slots (`TaskEntitySlot`, `TaskDraftSlot`, `TaskCompletionSlot`,
`TaskChildrenSlot`, `TaskRemindersSlot`, `TaskLifecycleSlot`, `TaskAiSlot`) plus collectors
(backlinks, logbook, proposals, time slot).

Intents: `TaskDetailIntent.Domain` (40+ variants sealed by marker interfaces), dispatched in one
exhaustive `when` at `TaskDetailCoordinator.kt:503`.

Events: `TaskDetailUiEvent` — `Error`, `Saved`, `SavedPulse`, `UndoDelete`, `UndoArchive`,
`UndoRemoveTag`, `UndoDeleteChecklistItem`, `UndoDeleteSubtask`, `UndoDeleteSeries`, `NavigateBack`.

---

## 5. Saving (the part that must not regress)

Debounce lives in `TaskDraftSlot`, not in the coordinator:

```kotlin
// TaskDraftSlot.kt:64-95
private val titleEdits = MutableSharedFlow<TaskDetailIntent.Domain.TitleChanged>(replay = 0, extraBufferCapacity = 4)
private val descriptionEdits = MutableSharedFlow<TaskDetailIntent.Domain.DescriptionChanged>(replay = 0, extraBufferCapacity = 4)

scope.launch {
    titleEdits.debounce(debounceMs).collect { edit ->
        if (edit.title.isBlank()) { draftState.revertTitle(); return@collect }
        val task = taskFlow.value ?: return@collect
        write(task, edit) { copy(title = edit.title) }
    }
}
```

- `debounceMs` is a DI parameter: `TaskContextDeps(clock, debounceMs = 300L)`
  (`domain/model/TaskDetailDeps.kt:173`).
- The write is a **read-modify-write with a re-read**: `UpdateTask.invoke(id, transform)`
  (`domain/usecase/UpdateTask.kt:30-39`) — *"Re-reads current state before applying transform — safe
  against concurrent modifications."* The full-entity overload is `@Deprecated("avoid
  stale-snapshot overwrites")`.
- `flush()` (`TaskDraftSlot.kt:141-156`) is called from `TaskDetailContent.kt:268` (back handler) and
  `:392`; there is no test for it.
- The draft distinguishes seed / echo / external rewrite by a `Revisioned` counter
  (`TaskDetailDraftState.kt:65-120`): a dirty draft wins over an external write; a clean draft takes
  it.

**This is genuinely good and should survive any redesign.** It is the reference implementation of
the "no TOCTOU, no Saved-spam" decision in the repo.

---

## 6. Sheets

One sealed type — `TaskEditorSheet` (`domain/state/TaskEditorSheet.kt`, 14 variants) — one router
(`TaskEditorSheetsHost.kt:51-285`), one chrome (`TaskEditorSheetHost`, `ModalBottomSheet` with drag
handle + ✕ + title + optional ✓).

| Variant | Has the shared chrome? | Renders |
|---|---|---|
| `Date` | ❌ | `MultiDatePickerSheet` or `DatePickerSheet` (own `Clear/Cancel/OK`) |
| `Time` | ❌ | `TimePickerSheet` (own buttons) |
| `StartDate`, `StartTime` | ❌ | same sheets |
| `Priority` | ✅ | `TaskEditorPrioritySheet` |
| `WorkflowState` | ✅ | `TaskEditorStateSheet` |
| `Project` | ✅ | `ProjectPickerSheet` (**wraps the host itself**) |
| `Tags` | ✅ | `TagsPickerSheet` (also wraps itself, plus `AddTagDialog` = `AlertDialog`) |
| `Recurrence` | ❌ | `RecurrencePickerSheet` (785 lines, own chrome) |
| `Checklist` | ✅ | `ChecklistEditorSheet` |
| `Attachments` | ✅ | `AttachmentsSheet` |
| `Dependencies` | ❌ | `RelatedTaskPickerSheet` |
| `Estimate` | ✅ | `TaskEditorEstimateSheet` |
| `TimeEntry` | ❌ | `TimeEntryEditorSheet` |

There is **no `Reminder` variant** — the single structural reason a reminder cannot be set.

`DialogState` (`core/ui/components/DialogState.kt`) is `remember`-backed and **does not survive
process death** (documented at L42-43), so a picker open during rotation is lost.

---

## 7. Design tokens

| Concern | File | Contents |
|---|---|---|
| Spacing | `presentation/theme/TaskTheme.kt:15-27` | `sm 8`, `md 12`, `lg 16`, `xl 24`, `screenPadding 16`, `cardCornerRadius 14`, `cardPaddingHorizontal 16`, `cardPaddingVertical 14`, `iconSize 22`, `iconSizeLarge 26` |
| Type | `core/ui/theme/Type.kt:53-106` | 15 Material slots + 15 `*Emphasized`, all scaled by the user's `fontSizeScale` |
| Colour | `core/ui/theme/SingularityTheme.kt` | `materialkolor.dynamicColorScheme(seed, dark, contrast = 0.5)` — 8 accent seeds |

**The detail does not use the type scale for its prominent text**: `22.sp` in `TaskTitleRow.kt:141`,
`16.sp` in `TaskDescriptionField.kt:41`, `TaskAttributeCard.kt:103`, `TaskEditorPriorityRow.kt:83`,
`TaskEditorDueDateRow.kt:97`, `TaskEditorEstimateRow.kt:81`. Only secondary text
(`labelMedium`, `bodySmall`, `bodyLarge`) goes through the theme, so `fontSizeScale` moves the small
text and leaves the title fixed.

---

## 8. Where a target-UX change lands

| Target change | Files |
|---|---|
| Remove the filled attribute cards | `TaskAttributeCard.kt:43-44`; rows already pass `isActive` |
| Unify row pitch | `TaskEditorContent.kt:193-201` (column), the three `TaskEditor*Row.kt` |
| Hide empty sections / add `+ Ещё` | `TaskDetailContent.kt:610-779`, `ExtraSectionCard.kt`, new parameter sheet in `TaskEditorSheetsHost` |
| Deadline row | `TaskEditorContent.kt` (new row after due date), `TaskEditorSheetsHost` (reuses `DatePickerSheet`) |
| Reminder row + sheet | `TaskEditorSheet.kt` (new variant), `TaskEditorSheetsHost`, new `ReminderSheetContent`, then dispatch `Domain.SetReminder` from the row |
| Recurrence text | `TaskDetailExtraSections.kt` + `RecurrenceFormatters.kt` |
| Title wrapping + bar title | `TaskTitleRow.kt:145`, `TaskDetailTopBar.kt:54` |
| IME insets | the Android host of `TaskDetailContent` (`Scaffold` at `:277`) |
| Checklist progress / collapse / drag | `TaskDetailExtraSections.kt` (checklist section), `ChecklistItemRow.kt` |
| Undo for checklist delete | `TaskDetailContent.kt:157-261` (already collects `UndoDeleteChecklistItem`), the section's delete call |
| Create parity with Detail | `TaskCreateScreen.kt:149-266` (call-site wiring), not the renderer |
| Localisation | every literal listed in `comparison/visual-analysis.md` §7 → `Res.string.*` |

---

## 9. Technical risks a redesign must respect

| Risk | Where | Note |
|---|---|---|
| Losing the debounced draft on navigation | `TaskDraftSlot.flush()` has **no test**; it is the only thing between a keystroke and a lost title | add a test before touching the screen |
| Re-introducing Saved-spam | `SavedPulse` vs `Saved` distinction (`TaskDetailUiEvent.kt:14,23`) is load-bearing | assert exactly one pulse in the new tests |
| Re-introducing TOCTOU | the write path re-reads; do not "optimise" it into `repo.update(snapshot)` | `UpdateTask.kt:30-39` |
| Two sources for checklist rendering | `TaskDetailChecklistSection` vs `ChecklistEditorSheet` | a change to one must be applied to both, or they must be merged |
| Process death loses an open picker | `DialogState` is `remember`-backed | acceptable, but do not put a half-typed value there |
| Duplicating state | adding a `reminder` field to `TaskDetailUi` would be a second source — the list already exists and is populated | wire the existing field |
| File size | `TaskDetailContent.kt` is 918 lines and `TaskEditorContent.kt` 737; the DoD's limit is 500 | extract sections while editing them, not in a separate refactor |
| Localisation gate | literals are everywhere; a gate would fail immediately | introduce it with a baseline, or per-file |
