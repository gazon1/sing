# Implementation plan

Derived only from findings in `comparison/ux-findings.md`. Nothing here is a refactor for its own
sake: every task changes something a user sees or can do.

**Do not start at task 1 and walk down the list.** Ship in the order of §1 — the first two
workstreams are P0 and gate everything else, and WS3 is small enough to land while WS4 is being
designed.

---

## 1. Order and rationale

| Wave | Workstream | Why this order | Ships independently? |
|---|---|---|---|
| 1 | **WS2 — IME & insets** | the only defect that stops a user from performing a basic action (typing a checklist item) | ✅ |
| 1 | **WS1 — make stored facts visible** (recurrence sentence, deadline, someday, reminder row) | a stored fact the user cannot see is data loss from their point of view | ✅ in four commits |
| 2 | **WS3 — visual system** (fill, contrast, rhythm, type slots, icons) | cheap, visible, and it makes every later screenshot reviewable | ✅ |
| 2 | **WS5 — title & bar** | depends on nothing, removes the worst readability defect on real titles | ✅ |
| 3 | **WS4 — sections & `Ещё`** | the structural change; needs WS3 to have already settled the surfaces | ✅ |
| 3 | **WS7 — checklist/subtasks** | the most-used sub-feature; needs WS4's section shell | ✅ |
| 4 | **WS6 — one scheduling sheet** | absorbs the reminder row from WS1 and the presets from WS4 | ✅ |
| 4 | **WS10 — localisation** | touches every string; do it after the strings stop moving | per-area |
| 5 | **WS8 — create/edit parity** | the largest; benefits from everything above being settled | partially |
| 5 | **WS11 — project picker** | already researched (`2862-…`); no new decisions needed | ✅ |
| 6 | **WS9 — actions reliability** | needs a reproduction first; may turn into a P0 | ✅ |
| 6 | **WS12 — footer, a11y sweep** | last, cheap, closes the DoD | ✅ |

Each wave is mergeable. A wave must leave the app **better and shippable**, not half-migrated — that
is why WS4 (which changes the screen's shape) comes after WS3 (which changes its surfaces) and before
WS6 (which moves rows between groups).

---

## 2. Workstreams

Format per task: context → current → target → findings → components → changes → acceptance →
tests → dependencies → priority/complexity.

---

### WS2-1 — IME and status-bar insets on the detail

- **Context:** the checklist input ends up behind the keyboard and the top bar slides under the
  status bar (`UXF-03`). The create screen already behaves correctly, so the fix is not new
  behaviour, it is the same behaviour in the other host.
- **Current:** `TaskDetailContent.kt:277` builds a `Scaffold` with no `contentWindowInsets`
  handling and no `imePadding()`; the scroll column is `verticalScroll` (`TaskEditorContent.kt:193`)
  with no `BringIntoViewRequester`.
- **Target:** with the IME open, the focused field and the row above it are fully visible; the app bar
  respects the status bar.
- **Findings:** UXF-03. **ADR:** none (this is a defect, not a decision).
- **Components:** `TaskDetailContent` (Scaffold), `TaskEditorContent` (column), the Android host
  (`androidApp`/shared `androidMain` entry if the insets come from there).
- **Changes:** apply `WindowInsets.safeDrawing` to the scaffold and `imePadding()` to the scroll
  container; attach a `BringIntoViewRequester` to the checklist/subtask input and request on focus.
  Do **not** change the create screen.
- **Acceptance:** with the IME open on the checklist input, the input's bottom edge is above
  `WindowInsets.ime`; the bar's top edge is below `WindowInsets.statusBars`; measured on the same
  720×1600 device.
- **Tests:** Compose UI test asserting the focused node's bounds do not intersect the IME inset (the
  Desktop harness cannot emulate an IME — use an Android instrumentation test); Maestro
  `TASK-CHECK-01` extended with an assertion that the input is visible after typing.
- **Dependencies:** none. **P0/S–M.**

---

### WS1-1 — Recurrence rule as a sentence

- **Context:** the card prints a Kotlin data class (`UXF-01`).
- **Current:** `TaskDetailExtraSections` renders the domain object's `toString()`.
- **Target:** `Каждые 3 дня, с 12 ноя 2026, до 29 янв 2025`.
- **Findings:** UXF-01. **ADR:** `2026-09-07-…` (pure formatters exist as a rule).
- **Components:** `TaskDetailExtraSections.kt`, `RecurrenceFormatters.kt`.
- **Changes:** one pure `formatRecurrence(rule, locale)`; use it in the row and in the picker's
  summary; add a test for every unit/interval/termination combination.
- **Acceptance:** no `toString()` of a domain type reaches a `Text`; a 3-day interval renders as a
  sentence.
- **Tests:** formatter unit tests (existing `RecurrenceFormattersTest` extended); a Compose test that
  the row does not contain `Interval(`.
- **Dependencies:** none. **P0/S.**

---

### WS1-2 — Deadline row

- **Context:** 150 imported tasks have `end_date`; the detail renders nothing (`UXF-20`).
- **Current:** `TaskEditorContent` has no deadline row; `tasks.end_date` exists.
- **Target:** a `Крайний срок` row directly under the due date, with the same date picker, a flag
  icon, `✕` to clear, and `Просрочен` when it is in the past and the task is open.
- **Findings:** UXF-20. **ADR:** new (this is a field becoming visible).
- **Components:** `TaskEditorContent.kt` (row), `TaskEditorSheetsHost` (reuse `DatePickerSheet`),
  `TaskEntitySlot` (intent already? verify), `TaskDetailIntent`.
- **Changes:** new row + intent + slot method; formatter for the deadline state.
- **Acceptance:** a task with `end_date` shows the row after install; clearing writes null; the
  overdue state is text, not only colour.
- **Tests:** slot unit test (write + clear), Compose test for the row and the overdue label,
  formatter test.
- **Dependencies:** WS4 decides the final row order. **P1/M.**

---

### WS1-3 — Someday / `Когда-нибудь`

- **Context:** 310 tasks have `someday = 1`; there is no surface (`UXF-20`).
- **Target:** a preset in the date sheet that sets `someday` and clears the date; the date row then
  reads `Когда-нибудь`.
- **Findings:** UXF-20. **ADR:** new.
- **Components:** `DatePickerSheet` (preset), `TaskEditorDueDateRow` (label), `TaskEntitySlot`.
- **Acceptance:** a task imported with `someday = 1` shows `Когда-нибудь` on open; the preset toggles
  it off.
- **Tests:** slot test, Compose test for the label, one Maestro flow.
- **Dependencies:** WS6 (the date sheet is rebuilt there — implement this *inside* WS6 if WS6 lands
  first). **P1/S.**

---

### WS1-4 — Reminder row and sheet

- **Context:** a reminder cannot be set at all (`UXF-02`).
- **Current:** `TaskDetailUi.reminders` populated and unread; `Domain.SetReminder` routed and never
  dispatched; no `TaskEditorSheet.Reminder`.
- **Target:** `⏰ Напоминание` row under the due date; sheet with offsets `в момент срока, 5, 10, 15,
  30 мин, 1 ч, 2 ч, своё время`; pre-selects the current reminder; `Удалить` in the sheet; `✕` on the
  row; a permission denial shows `Уведомления выключены` with a settings action.
- **Findings:** UXF-02. **ADR:** `2026-09-07-…` implied it (bottom bar "Remind"), DoD §4 requires it.
- **Components:** `TaskEditorSheet.kt` (+1 variant), `TaskEditorSheetsHost`, new
  `ReminderSheetContent` (bind to the existing `TaskRemindersSlot`), `TaskEditorContent`
  (row), `TaskDetailIntent` dispatch site.
- **Changes:** the row is the *only* missing piece — the slot, repository and scheduler are done and
  tested (`TaskRemindersSlotTest`).
- **Acceptance:** a reminder can be added, changed, cleared; the row shows the offset; the
  notification fires on time; the value survives a process restart.
- **Tests:** `TaskRemindersSlotTest` already covers the writes; add a Compose test for the row and
  the sheet's pre-selection, and a Maestro flow that sets a reminder and sees the row.
- **Dependencies:** WS6 for the final sheet chrome. **P0/M.**

---

### WS3-1 — Remove the accent fill from attribute rows

- **Context:** filled rows are `primaryContainer` + `onSurface` = 3.67:1 and the fill changes with the
  theme seed (`UXF-08`).
- **Current:** `TaskAttributeCard.kt:43-44`.
- **Target:** every row on `surface`; "set" is conveyed by the icon tint (already implemented) and a
  medium weight on the value.
- **Findings:** UXF-08. **ADR:** supersedes the visual half of `2026-09-07-…`; record it.
- **Acceptance:** no `primaryContainer` in `feature/tasks`; every row contrast ≥ 4.5:1.
- **Tests:** a theme test that walks 8 accents × 2 modes and asserts the ratio of
  `onSurface`/`surface` and `onSurfaceMuted`/`surface` and `onSurfaceMuted`/`surfaceVariant`.
- **Dependencies:** none. **P1/S.**

---

### WS3-2 — Muted token with AA contrast

- **Context:** placeholder 2.06:1 (`UXF-09`).
- **Target:** `onSurfaceMuted` ≥ 4.5:1 on `surface` and on `surfaceVariant`; used for placeholders,
  hints, footer.
- **Findings:** UXF-09. **Components:** `SingularityTheme.kt`/`SemanticColors.kt`,
  `TaskDescriptionField`, `ExtraSectionCard`.
- **Acceptance:** no text pair in the detail below 4.5:1 (test-enforced).
- **Tests:** same theme test as WS3-1. **P1/S.**

---

### WS3-3 — One vertical rhythm

- **Context:** 76dp pitch alternating with 52dp cards and an 84dp hero gap (`UXF-10`).
- **Target:** 48dp rows, 8dp inside a group, 16dp between groups, 16dp hero top.
- **Components:** `TaskEditorContent.kt:193-201`, `TaskEditorPriorityRow/DueDateRow/EstimateRow`.
- **Acceptance:** on a 360×800 screen with a date and a project, the first checklist row is visible
  without scrolling.
- **Tests:** a Compose test that measures the row height (the repo already has this shape of test in
  `TaskDetailFoldFlowTest`).
- **Dependencies:** WS3-1. **P2/S.**

---

### WS3-4 — Typography through the theme, not literals

- **Context:** 22sp/16sp literals ignore `fontSizeScale` (`visual-analysis.md` §3).
- **Target:** `titleLarge`/`bodyLarge` slots (+ one `titleEmphasized` for SemiBold); a test asserts
  the detail's text scales.
- **Components:** `TaskTitleRow.kt:141`, `TaskDescriptionField.kt:41`, `TaskAttributeCard.kt:103`,
  the three rows.
- **Acceptance:** with `fontSizeScale = 1.3` the title grows and nothing clips.
- **Tests:** a Compose test that reads two font sizes at two scales. **P2/S.**

---

### WS3-5 — One icon language

- **Context:** 📋 emoji among Material icons (`UXF-18`, ADR D5).
- **Target:** `Icons.Filled.Checklist` etc.; zero emoji in `feature/tasks`.
- **Tests:** a source-scanning arch test that fails on a PUA emoji literal **or its `\uXXXX` escape**
  in `feature/tasks` (the escape is why the existing grep missed it).
- **P2/S.**

---

### WS3-6 — Priority: one glyph, no mark for the default

- **Context:** ★ in lists and flag in the detail; `High` and `Urgent` share a colour (`UXF-14`).
- **Target:** flag everywhere; `None`/`Medium` unflagged; `Urgent` filled + `error`, `High` outline +
  `error`.
- **Components:** `TaskMarkingsRow.kt:148-191`, `PriorityIndicator.kt`, `TaskEditorPrioritySheet`.
- **Tests:** list-row golden-ish Compose test: a `Medium` task has no priority mark, a `High` has one.
  **P2/S.**

---

### WS4-1 — Section rules and the `Ещё` sheet

- **Context:** six empty sections (`UXF-04`); the DoD test currently encodes the opposite rule.
- **Target:** §2–§3 of `target-ux.md`; a `+ Ещё` row derived from what is hidden.
- **Findings:** UXF-04, UXF-15, UXF-25. **ADR:** replaces the "empty sections" rule of
  `2026-09-07-…` → **write the superseding ADR before the code**.
- **Components:** `TaskDetailContent.kt:610-779`, `ExtraSectionCard.kt`, new
  `TaskEditorSheet.MoreParameters` + `ParametersSheet`, `TaskEditorSheetsHost`.
- **Changes:**
  1. checklist/subtasks become one-line headers when empty (no hint sentence);
  2. attachments/logbook/related/time render only when non-empty;
  3. workflow state renders only when set;
  4. `+ Ещё` row above the footer opens a sheet that adds a parameter **and opens its editor**;
  5. update `TaskDetailEmptyChildSectionsTest` to the new rule (it asserts the old one).
- **Acceptance:** a title-only task's body is ≤ 1 screen at 360×800; every parameter remains
  reachable in ≤ 2 taps; the empty-state tests assert the new rule.
- **Tests:** rewrite `TaskDetailEmptyChildSectionsTest`; new Compose tests for the `Ещё` sheet
  (hidden list matches the body); Maestro `tasks-open-task-detail` re-recorded.
- **Dependencies:** WS3-1/WS3-3 (surfaces must be settled). **P1/M.**

---

### WS5-1 — Title wraps, bar names the task

- **Context:** titles clip with no ellipsis; the bar says `Tasks` (`UXF-06`, `UXF-07`).
- **Target:** 3 lines + ellipsis; the bar shows an ellipsised title once the hero scrolls out.
- **Components:** `TaskTitleRow.kt:145`, `TaskDetailTopBar.kt:54`.
- **Acceptance:** a 90-character title is fully readable at 360dp; the bar title appears only after
  the hero leaves the viewport.
- **Tests:** Compose test with the real longest imported title; screenshot re-record.
- **P1/S.**

---

### WS5-2 — Completion is visible

- **Context:** a completed task looks open (`UXF-23`).
- **Target:** struck/dimmed title, `Выполнено 12 ноя 2026` instead of the due row, clear affordances
  hidden, state row disabled (already the case).
- **Components:** `TaskTitleRow`, `TaskEditorContent`, `TaskAttributeCard`.
- **Tests:** Compose test asserting the strikethrough and the replaced date row. **P2/S.**

---

### WS6-1 — One scheduling sheet

- **Context:** date opens below the fold, time is a separate sheet, reminder is missing, presets are
  two (`UXF-16`).
- **Target:** the wireframe in `wireframes/task-detail.md` §6 (presets, calendar, reset, time row,
  reminder row, one commit).
- **Findings:** UXF-16, UXF-20. **ADR:** supersedes the "three sheets" shape; record it.
- **Components:** `TaskEditorSheetsHost` (`Date` variant), `DatePickerSheet`, `TimePickerSheet`
  (embedded as a row), `ReminderSheetContent`.
- **Acceptance:** on a 360×800 screen, presets, calendar and the commit are all visible without
  scrolling; one `OK` commits date+time+reminder together.
- **Tests:** Compose test measuring sheet content height vs window; Maestro `02-set-due-date` and
  `03-due-date-cancel` updated.
- **Dependencies:** WS1-3, WS1-4. **P1/M–L.**

---

### WS7-1 — Checklist: add below, progress, collapse

- **Context:** UXF-18 (a, b, c).
- **Target:** add row at the bottom; `LinearProgressIndicator` when total > 0; collapse to 5 rows
  with `Показать все N` when total > 5.
- **Components:** `TaskDetailExtraSections.kt` (checklist section), `TaskEditorContent` (row).
- **Acceptance:** adding an item leaves the new item visible above the IME; a 26-item checklist
  occupies ≤ 5 rows until expanded.
- **Tests:** Compose test for the collapse threshold and the progress fraction; Maestro
  `TASK-CHECK-01`.
- **Dependencies:** WS4-1. **P1/M.**

---

### WS7-2 — Checklist delete with undo, reorder

- **Context:** UXF-18 (d, e), UXF-19.
- **Target:** swipe-to-delete (or a trailing `⋮` with delete) + 4 s undo snackbar via the existing
  `UndoDeleteChecklistItem`; long-press drag persisting `sort_order`.
- **Components:** `ChecklistItemRow.kt`, `TaskDetailExtraSections.kt`,
  `TaskChildrenSlot` (reorder method is new).
- **Acceptance:** deleting offers undo for 4 s; the undo restores the item **with its row index**
  (already implemented for delete — verify for reorder).
- **Tests:** slot test for reorder persistence; Compose test for swipe+undo; Maestro
  `tasks-checklist`.
- **Dependencies:** WS7-1. **P1–P2/M.**

---

### WS8-1 — Create and Edit share one contract

- **Context:** UXF-05, UXF-12, UXF-24.
- **Target:** the table in `target-ux.md` §5; landing on the new task's detail after save; no
  pristine error; identical field set.
- **Findings:** UXF-05, UXF-12, UXF-24. **ADR:** amends `2026-09-05-…`.
- **Components:** `TaskCreateScreen.kt:149-266` (wiring), `TaskCreateViewModel`,
  `TaskEditorContent` (already supports the fields), `EditorBackGuard`.
- **Changes:** pass the missing attributes into the create model; move the project row to its detail
  position; replace `titleError` with a "touched" flag; navigate to `TasksRoute.Detail` after save.
- **Acceptance:** every field present on the detail is present on create in the same order; no red
  text before interaction; after saving, the user is on the new task.
- **Tests:** `TaskCreateViewModelTest` extended; `CreateTaskFlowTest` (desktop) for the field order;
  `EditorBackGuardTest` unchanged; Maestro `13-create-full-task`.
- **Dependencies:** WS3, WS4. **P1/L.**

---

### WS9-1 — Reproduce and fix the overflow reliability

- **Context:** UXF-13 — first tap lost, menu items not clickable as text nodes.
- **Target:** one tap opens; one tap acts.
- **Approach:** first write a failing Compose test (tap `⋮` once, expect one menu). Then check the
  "publish actions to the Android shell" path (`TaskDetailTopBar.kt:35-41`) and whether the shell
  menu and the Compose `DropdownMenu` are both active. Only then change code.
- **Acceptance:** the test passes on device; Archive/Delete stay reachable in one tap.
- **Tests:** new Compose/instrumentation test; Maestro `05-archive-via-menu`, `06-delete-undo`.
- **Dependencies:** none, but **do not** "fix" it by adding a second entry point. **P1/S–M.**

---

### WS10-1 — One language

- **Context:** UXF-21.
- **Target:** every user-visible literal in `feature/tasks` comes from `Res.string.*`; one language on
  screen.
- **Components:** every file listed in `visual-analysis.md` §7.
- **Acceptance:** a source scan finds no user-visible literal in `feature/tasks`; the app renders in
  the device locale.
- **Tests:** new arch test with a baseline of allowed literals that may only shrink.
- **Dependencies:** after WS1/WS4/WS6 so the strings stop moving. **P1/M.**

---

### WS11-1 — Project picker (reuse the finished research)

- **Context:** UXF-17; `docs/research/2862-project-navigation-comparison.md` already specifies the
  picker (tree order, indent, emoji, 48dp, one line, search, "Add project").
- **Target:** implement that P0/P1 list. **No new UX decisions here.**
- **Tests:** the ones named in that document (hierarchy indent, emoji rendered, add-and-select,
  `ProjectPickerDismissTest` unchanged).
- **P1/M.**

---

### WS12-1 — Footer metadata and the accessibility sweep

- **Context:** UXF-25; DoD §8.
- **Target:** `Создана … · изменена …` footer; `contentDescription` on the checklist checkbox;
  heading semantics on the title; a11y labels for every icon button.
- **Tests:** Compose test asserting the semantics label on the row checkbox; TalkBack pass on the
  three fixture tasks.
- **P3/S.**

---

## 3. Test strategy

Six tiers, each with a job the others cannot do:

| Tier | Job | Where |
|---|---|---|
| **Pure unit** | formatters, draft state machine, slot logic | `shared/src/commonTest` |
| **ViewModel/slot** | intent → repository write, undo events, exactly-one `Saved` | `commonTest/.../viewmodel/slot/` |
| **Compose (Desktop)** | layout contracts — order, presence, collapse thresholds, clickability | `desktopApp/src/jvmTest/.../flows/tasks/` |
| **Compose (Android instrumentation)** | IME insets, status bar, keyboard, back handling | `androidApp/src/androidTest` |
| **Maestro (device)** | end-to-end journeys on the real imported profile | `Maestro/flows/tasks/` |
| **Screenshot comparison** | visual regressions on Android | **does not exist today** — see below |

### Screenshot comparison

`docs/decisions/2026-09-30-roborazzi-not-built-superseded.md` records that the planned Roborazzi
setup was dropped because the components it named no longer exist. For this work we need it again,
with a smaller scope:

- **What to capture:** the five states in `wireframes/task-detail.md` §1–§5, at 360×800, light theme,
  Blue accent, font scale 1.0, on the same seed data.
- **Seeds:** use the fixtures in `test-data.md` (`Купить хлеб`, `Backlog sing features`, a completed
  task, a title-only task) so before/after images differ only by the change.
- **Determinism:** fixed clock (`Clock.System.now()` must be injected — `DatePickerSheet` currently
  calls it directly, which would make a screenshot flaky), no relative timestamps in the captured
  region, no animations.
- **Threshold:** pixel-diff with a reviewed baseline per state; a diff requires a note in the PR.

Until that exists, the before/after evidence is the paired screenshots in this folder
(`singularity-todo/screenshots/` = before), re-taken with `.tools/shot.sh` after each wave.

### What must be green before a wave merges

```
./check.sh                 # ktlint → compile-changed, the repo's own gate
./gw :shared:jvmTest -Ptest.tags=fast
easement check
```

plus the wave's own tests from `testing/acceptance-criteria.md`.

---

## 4. Explicitly out of scope

- Reworking `TaskDetailCoordinator` into one VM with `TaskCreateViewModel`. Two VMs is a defensible
  split; the *contract* must match, not the class count.
- Replacing the slot architecture.
- Adding a bottom action bar for its own sake. The DoD asks for one; the findings do not. If Archive
  and Delete cannot be made reliable in the overflow (WS9), move them to a bar — otherwise do not add
  a bar just to satisfy a checklist.
- Any change to recurrence semantics, agenda selectors or sync.
- Migrating `DatePickerSheet`'s `Clock.System.now()` — worth doing, but it is a prerequisite of the
  screenshot tier, not a user-visible fix.
