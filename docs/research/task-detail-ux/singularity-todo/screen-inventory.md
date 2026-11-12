# Singularity Todo — screen and state inventory

All images in `screenshots/`, taken on `0O65321F32204DD5` (720×1600 px, density 320 → **1dp = 2px**),
light theme, font scale 1.0. Coordinates in this document are px as measured; ÷2 for dp.

The screen is one long scroll. What follows is its anatomy, top to bottom, with every state that
changes what is drawn.

---

## 0. Screenshot index

| File | What it shows |
|---|---|
| `01-today-list.webp` | Today list — the entry point, and what a task row looks like |
| `02-task-detail-minimal-top.webp` | Detail, top of a typical task: checkbox + title, description, workflow state, priority, estimate, date, project |
| `03-task-detail-empty-sections.webp` | Same task, mid-scroll: pin, Related tasks, **Repeats (raw toString)**, Checklist |
| `04-task-detail-bottom-empty.webp` | Same task, bottom: Subtasks, Attachments, Time, Logbook |
| `05-overflow-menu.webp` | Overflow: `Архивировать`, `Удалить` |
| `06-priority-sheet.webp` | Priority sheet, 5 levels, current one selected |
| `07-date-sheet-collapsed.webp` | Date sheet as it opens — calendar cut off, no actions visible |
| `08-date-sheet-expanded.webp` | Date sheet after a scroll — calendar + `Clear / Cancel / OK` |
| `09-project-picker.webp` | Project picker — flat A→Z, 52dp rows |
| `10-state-sheet.webp` | Workflow state sheet — `No state / Next up / In progress` |
| `11-estimate-sheet.webp` | Estimate sheet — 5/15/30 min, 1 h, 2 h, None, Custom |
| `12-recurrence-raw-tostring.webp` | The recurrence card printing a Kotlin data-class dump |
| `13-checklist-add-keyboard-covers-field.webp` | Keyboard open on the checklist input — the input is behind the IME |
| `14-checklist-with-item.webp` | One checklist item: 18dp square, red ✕ delete, header counter `(0/1)` |
| `15-rich-task-top.webp` | `Backlog sing features` — top of a task with 26 checklist items |
| `16-rich-checklist-26-items.webp` | The same checklist, multi-line items, delete ✕ per row |
| `17-rich-checklist-scrolled.webp` | Further into the same checklist |
| `18-completed-task.webp` | A completed task — only the checkbox changes; the title is clipped at the right edge |
| `19-create-screen.webp` | Create screen, pristine: `Title is required` already in red |
| `20-create-typing-keyboard.webp` | Create screen while typing — the project row appears only now |
| `21-create-discard-dialog.webp` | `Discard changes?` after Back on a dirty create form |
| `22-title-edit-quick-actions.webp` | Editing the title — underline, `Saved` in the bar, purple `Quick actions` card |
| `23-delete-undo-snackbar.webp` | `Task deleted` + `Undo` after deleting from the overflow |
| `24-ai-actions-sheet.webp` | `AI Actions for: …` — 6 actions, the list runs past the sheet |
| `25-search-results.webp` | Search results — the route by which rich tasks were reached |
| `26-archive.webp` | Archive list (`Menu → Archive`) |

---

> Screenshots are stored as **WebP** (`*.png` is gitignored repo-wide; the existing research
> assets use WebP for the same reason). Two file names exist in both app folders
> (`01-today-list`, `06-priority-sheet`); outside an app folder a bare file name means
> **Singularity Todo**, and a `singularity-app/…` prefix means the reference app.

## 1. Entry point: the Today list (`01`)

Row anatomy, measured from `uiautomator` bounds:

| Element | Bounds (px) | Size |
|---|---|---|
| completion checkbox (circle) | `[54,617][114,677]` | 60px = **30dp** visual, 48dp target |
| priority mark (★) | at x≈166 | 24px = 12dp, coloured by priority |
| title | `[204,614][688,706]` | 2–3 lines, `bodyLarge` 16sp, no `maxLines` |
| meta line | `[160,716][688,758]` | date (`Чт, 12 ноя 2026`), clock, recurrence, note, project dot + name |
| divider | full width, `[48,785]` | 1px |

Notes:

- **Every row carries a ★.** 11 301 of 12 027 tasks are `Medium`, so the star is a coloured
  decoration on almost every row; only `High` (8 tasks) differs in colour. The same concept is a
  **flag** inside the detail (`06-priority-sheet.webp`), so priority has two glyphs and two colour
  languages in one app.
- **The FAB overlaps content.** `+ Add task` sits over the fourth row's text
  (`01-today-list.webp`, y≈1150–1310).
- The list is the only place a task can be completed by one tap; the detail's checkbox does the same.
- Cold start shows a `Checking version…` gate for several seconds before this list.

---

## 2. Task Detail — anatomy

One `LazyColumn`/scroll column, no top-bar title, no bottom bar. Section order as rendered (verified
on device and against `TaskEditorContent.kt:203-424` + `TaskDetailContent.kt:610-779`):

| # | Block | Present when | Screenshot |
|---|---|---|---|
| 1 | **Hero**: checkbox + title field + description field | always | `02` |
| 2 | **Quick actions** (`Ask AI` only) | only on a first-run task | `22`, `24` |
| 3 | **Workflow state** row (`No state` / `Next up` / `In progress`) | always | `02`, `10` |
| 4 | **Priority** row + clear ✕ | always | `02` |
| 5 | **Estimate** row (`Добавить оценку`) | always | `02`, `11` |
| 6 | **Due date** row + clear ✕ | always | `02`, `07` |
| 7 | **Project** row + clear ✕ | always | `02`, `09` |
| 8 | **Tags** row (`Add tags`) | always | `15` |
| 9 | **Repeats** card | only when a rule exists | `12` |
| 10 | **Pin** row (`Not pinned`) | always | `03` |
| 11 | **Related tasks** card | always, with hint | `03` |
| 12 | **Tags** card | only when non-empty | — |
| 13 | **Checklist** card | always, with hint + input | `03`, `13`, `14` |
| 14 | **Subtasks** card | always, with hint + input | `04`, `16` |
| 15 | **Attachments** card (`+ Add file`) | always | `04` |
| 16 | **Series** card | only in a series | — |
| 17 | **Time** card (`Start`, `Add manual entry`) | always | `04` |
| 18 | **Logbook** card | always, with hint | `04` |
| 19 | Bottom navigation bar (`Inbox / Today / Upcoming / Calendar / Menu`) | **always, even here** | all |

**Six of those blocks cannot be empty-but-hidden.** On `Купить хлеб` — a task with a title, a date,
a project and a recurrence — blocks 11, 13, 14, 15, 17 and 18 each render a hint sentence, and the
scroll is ~2 screens long (`03`, `04`).

---

## 3. States

### 3.1 Title

| State | Rendering | Evidence |
|---|---|---|
| empty | placeholder `Введите описание задачи...` for description; the title field shows an empty line and no placeholder | `22` (after clearing) |
| short | 22sp SemiBold, one line | `02` |
| long | **clipped at the container edge with no ellipsis** — `Отмамамыларамливаю к` | `18` |
| multi-line | not possible: `TextFieldLineLimits.SingleLine` (`TaskTitleRow.kt:145`) | `18` |
| editing | text gets an underline, `Saved` pill appears in the top bar | `22` |
| cleared | rejected: the draft reverts to the stored title (`TaskDraftSlot.kt:80-83`) | — |
| saving | debounced 300 ms, silent; no snackbar | `22` |

### 3.2 Description

- Inline, no border, placeholder `Введите описание задачи...`.
- Grows with content; no toolbar, no markdown, no attachments inside it.
- Same debounce/silent-save as the title.

### 3.3 Workflow state (`10`)

`No state` / `Next up` / `In progress`, applied on tap, sheet closes itself. Three strings with no
explanation, and no `Done` — completion is the checkbox. For a completed task the row is drawn but
is not clickable (`TaskAttributeCard(enabled = false)`).

### 3.4 Priority (`06`)

Five levels (`No priority`, `Low`, `Medium`, `High`, `Urgent`), coloured flag per level, radio shows
the current one. Tap applies immediately and closes the sheet. **`High` and `Urgent` use the same red
flag** — the only difference is the label.

### 3.5 Due date (`07`, `08`)

- Opens as a bottom sheet at **partial height**: the calendar is cut off and **no `Clear / Cancel /
  OK` is visible**. A scroll inside the sheet reveals them (`08`).
- Presets: `Сегодня`, `Завтра` (Russian) next to `Clear / Cancel / OK` (English).
- The selected day is painted in Material's navy `#00305F`-family, a different blue from the app's
  primary `#46709E`.
- Clearing also exists as the ✕ on the row, so `Clear` inside the sheet is a second route.

### 3.6 Project (`09`)

Flat A→Z list of 132 projects, 52dp pitch, grey `Folder` for all, no emoji, no hierarchy, no search,
no "create project", no highlighted current value in the visible area. The same defects are recorded,
measured, in `docs/research/2862-project-navigation-comparison.md` — they are unchanged.

### 3.7 Estimate (`11`)

Chips `5 min · 15 min · 30 min · 1 h · 2 h · None`, plus `Custom (minutes)` with an input.

### 3.8 Recurrence (`12`)

The card prints the model: `Interval(base=FROM_DUE, amount=3, unit=DAY,
termination=RecurrenceTermination(endDate=2025-01-29))`. This is a `toString()` of the domain object
(`TaskDetailExtraSections.kt`), and it is the single worst user-facing string found in this study.

### 3.9 Checklist (`03`, `13`, `14`, `16`, `17`)

| State | Rendering |
|---|---|
| empty | `No checklist items yet. Add the first one below.` + `Add item...` field + `+` |
| one item | header becomes `Checklist (0/1)`; item = 18dp square, label, red ✕ delete |
| many items | header `Checklist (4/26)`; a 26-item checklist fills ~3 screens; no collapse, no progress bar |
| completed item | square fills; label is **not** struck through on the detail (the shared row used by the sheet does strike it) |
| long item | wraps to 5 lines; the ✕ is vertically centred against the middle of the text |
| reorder | not supported anywhere in the app |
| delete | immediate, no undo, no confirmation (`14` → `03`) |

The add field sits **above** the items, which is the opposite of every comparable app.

### 3.10 Subtasks (`04`)

Same shape as the checklist: hint, `Add subtask...` field, rows. Title tap opens the subtask's own
screen (`ChecklistItemRow(onOpen = …)`). Header uses a **📋 emoji** as its icon
(`SubtasksSection.kt:66` — `Text("\uD83D\uDCCB")`), next to Material icons for Attachments and Time,
in the same column.

### 3.11 Attachments (`04`)

Empty card with a bare `+ Add file` **text link**. Wired to a file picker
(`rememberAppFilePicker(FilePickPurpose.Attachment)`).

### 3.12 Time / Logbook (`04`)

`Time`: `Start` button, `No time tracked yet`, `Add manual entry`.
`Logbook`: `+` in the header, hint text when empty.

### 3.13 Completed task (`18`)

- Checkbox becomes a filled dark circle with a white tick (a11y label `Задача выполнена`).
- **Nothing else changes**: the title keeps its colour and weight, no strikethrough, no dimming, and
  the finished task keeps its date row and its ✕ clear affordances.
- The long title is clipped (see 3.1).

### 3.14 Overflow (`05`, `23`)

`Архивировать` / `Удалить`, in Russian, text-only, no icons, positioned over the title. Delete fires
immediately — no confirmation — and shows a `Task deleted` snackbar with `Undo` for a few seconds
(`23`).

---

## 4. The Create screen (`19`, `20`, `21`)

A different screen with a different contract:

| | Detail | Create |
|---|---|---|
| Save | implicit, debounced | explicit `Сохранить` button, disabled while the title is empty |
| Validation | none surfaced | `Title is required` **on a pristine form**, before any input |
| Back | always leaves, draft is flushed | `Discard changes?` dialog when dirty (`21`) |
| Fields | 13 rows + 6 empty sections | title, description, Checklist, Attachments, priority, date, start date, project |
| Missing vs detail | — | tags, recurrence, reminder, estimate, workflow state |
| Project row | 5th row, always visible | **appears only after the title is non-empty** (`19` → `20`) |
| Inserted card | `Quick actions` (first run only) | — |

So the two halves of the same feature differ in: how saving works, how errors appear, how Back
behaves, which fields exist, and where the project row sits.
