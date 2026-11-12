# Target UX — the Task Detail we should build

This is a specification, not a mock. It states the rule for every element: when it appears, where it
is edited, when it is saved, how it is cleared, and what the user sees afterwards. Wireframes are in
`wireframes/task-detail.md`.

Constraints it respects:

- Compose Multiplatform, one renderer for Android and Desktop (`TaskEditorContent` already is that
  renderer) — no platform fork.
- The existing state model: `TaskDetailCoordinator` + slots + `TaskDraftSlot`'s debounced write. The
  target adds no second source of truth.
- 360×800dp phone is the reference viewport; Desktop must not regress.

---

## 1. Information architecture

Five groups, in this order. The order is the reading order of a person deciding "what is this and
when do I do it".

| # | Group | Contents | Why here |
|---|---|---|---|
| **A** | **Identity** | completion checkbox, title, description | what the task *is*; must dominate |
| **B** | **Planning** | due date + time, reminder, repeat, deadline, someday | *when*; the group SingularityApp puts in one card and the group users open a task for |
| **C** | **Organisation** | project, tags, priority, workflow state | *where it belongs* and *how important* |
| **D** | **Content** | checklist, subtasks, attachments, description-as-document (links, notes) | *what has to be done*; grows without limit |
| **E** | **Optional / record** | estimate, time tracking, logbook, series, related tasks, created/updated | everything that is only interesting sometimes |

Two rules govern the whole screen:

1. **A group with nothing set collapses to one line or disappears** — never to a hint sentence per
   section (UXF-04).
2. **Nothing in the body is painted with an accent fill.** Accent is for icons of active values and
   for primary buttons (UXF-08).

---

## 2. Display rules per element

| Element | Shown when | Lives in | Edit surface | Rule |
|---|---|---|---|---|
| Checkbox | always | A | tap | completion toggle; one tap, no confirm |
| Title | always | A | inline, up to 3 lines | autosave 300 ms; empty → revert silently |
| Description (short) | always | A | inline | placeholder only, no border; autosave |
| Description (document) | when it grows past ~6 lines **or** the user taps "Открыть документ" | A → C? no: stays in A | full-sheet rich editor | one surface, two modes; the sheet is `ModalBottomSheet` full height with `✕ / Описание / ✓` |
| Due date | **always** (this is the one planning field that must be visible) | B | sheet | `Clear` inside the sheet, `✕` on the row |
| Due time | when a date exists | B | same sheet as the date (time row) | |
| Reminder | when set; otherwise a row inside **B** that reads `Напоминание` | B | sheet (`Reminder` variant) | pre-selects the current offset; `Удалить` inside; `✕` on the row |
| Repeat | when a rule exists | B | `RecurrencePickerSheet` | row text is a **sentence** from a pure formatter (UXF-01) |
| Deadline | when set **or** the section is expanded | B | same date sheet, `flag` icon | rendered `Просрочен` when < today and open |
| Someday | when `someday == true` | B | date sheet preset | replaces due date; the date row reads `Когда-нибудь` |
| Priority | always | C | sheet | one glyph (flag) everywhere; **no mark for the default level** |
| Project | always | C | picker sheet | row reads `Без проекта` when null; ✕ clears |
| Tags | when non-empty, otherwise one `+ Метки` row | C | picker sheet | |
| Workflow state | **only when set**, or via `+ Ещё` | C | sheet | never a 52dp empty row above priority (UXF-15) |
| Checklist | always — as a **one-line header with `+`** when empty | D | inline add at the **bottom** of the list | progress bar + `4/26` when non-empty; collapse when > 5 |
| Subtasks | same rule as checklist | D | inline add at the bottom | header icon is Material, not 📋 |
| Attachments | when non-empty, or via `+ Ещё` | D | sheet | |
| Estimate | via `+ Ещё` | E | sheet | |
| Time tracking | when running, or when it has entries, or via `+ Ещё` | E | inline Start/Stop + manual entry | |
| Logbook | when non-empty, or via `+ Ещё` | E | `+` in the header | |
| Series | only when the task belongs to a series | E | read-only card | |
| Related tasks | when non-empty, or via `+ Ещё` | E | picker | |
| Created / updated | always, as one muted footer line | E | read-only | `Создана 12 ноя 2026 · изменена 2 мин назад` |
| **`+ Ещё`** | always, last row before the footer | — | parameter sheet | lists every element currently hidden, each with a `+` |

"Via `+ Ещё`" means: the element is **not in the body** until the user adds it, and it stays after
that because it now has a value. This is the mechanism that replaces six empty sections — and unlike
a blanket "hide when empty", it keeps a discoverable path to every field (the failure mode of the
reference app, whose `Больше параметров` is the only way in and is at the very bottom).

---

## 3. Empty-state rule (replaces the current one)

| State | Now | Target |
|---|---|---|
| Nothing but a title | 6 cards with hints, ~2 screens | hero + `Срок` row + `Приоритет` + `Без проекта` + `Чек-лист +` + `Ещё +` + footer ≈ **one screen** |
| Checklist empty | card + hint + input | header row `Чек-лист` with `+`; tapping `+` opens the inline input at the top of the (now non-empty) list |
| Attachments empty | card + `+ Add file` | not drawn; reachable from `Ещё` |
| Logbook empty | card + hint | not drawn; reachable from `Ещё` |
| Related empty | card + hint + link | not drawn; reachable from `Ещё` |

Rule of thumb, in one sentence: **a group is drawn when it has content; a group's entry point is
always reachable in at most one extra tap.**

---

## 4. Interaction rules per field

For every editable attribute the same six questions must have an answer. This table *is* the contract.

| Field | Opens edit | Edited where | Saved when | Cancelled by | Cleared by | Error shown |
|---|---|---|---|---|---|---|
| Title | tap on the text | in place | 300 ms after the last keystroke | IME Done / focus loss (flush) | not allowed — empty reverts | only after a failed explicit save (create) |
| Description | tap | in place | 300 ms | same | emptying it | same |
| Date | tap on the row | sheet | on `OK` | sheet's `Отмена` or a swipe-down | `Сбросить` in the sheet, `✕` on the row | never (it is a picker) |
| Time | time row inside the date sheet | sheet | with the date's `OK` | same | `✕` on the time row | never |
| Reminder | tap on the row | sheet | on `Добавить` | dismiss | `Удалить` in the sheet, `✕` on the row | permission denial → row shows `Уведомления выключены` + a settings action |
| Repeat | tap | sheet | on confirm | dismiss | `Убрать повторение` | invalid rule (end before start) → the confirm is disabled with a reason |
| Deadline | tap | sheet | on `OK` | dismiss | `Сбросить` | never |
| Priority | tap | sheet | **on tap of a level** (keep today's behaviour) + closes | dismiss | `✕` on the row | never |
| Project | tap | picker | **on tap of a project** + closes | dismiss | `Без проекта` row, `✕` | never |
| Tags | tap | picker | on `Готово` | dismiss | per-tag `✕` on the chip | never |
| State | tap (only when set) | sheet | on tap | dismiss | `Нет состояния` | never |
| Checklist item | `+` | inline row | on IME action / `+` | empty input = no-op | swipe or `✕` **with undo** | blank title is a no-op, not an error |
| Subtask | `+` | inline row | same | same | same | same |
| Attachment | `+` | file picker | on pick | system cancel | `✕` on the tile, undo not needed (file stays on disk) | unsupported type → snackbar |

Two universal rules:

- **Every commit that the user did not ask for is silent.** A snackbar appears only for actions the
  user performed deliberately (delete, archive, reminder permission).
- **Every destructive action is reversible for 4 s** via the existing `NotificationHost` snackbar —
  the events already exist (`UndoDeleteChecklistItem`, `UndoDeleteSubtask`, `UndoRemoveTag`,
  `UndoArchive`, `UndoDelete`).

---

## 5. One contract for Create and Edit

| Aspect | Target |
|---|---|
| Surface | Create = full screen (a task has no context to preserve); Edit = the detail. Both render `TaskEditorContent`. |
| Fields | identical **set and order** in both, including the `+ Ещё` row — it is the same renderer, so a difference here would be a deliberate difference with no reason behind it |
| Commit | Create autosaves a draft (it already does — `TaskCreateDebounceTest`) and `Сохранить` means "done"; the draft survives Back with a prompt only if it was **never** saved. |
| Validation | the red error appears after a save attempt or a blur, never on a pristine form (UXF-12). |
| Saving indicator | the same `Saved` pill in the bar; no snackbar. |
| Back | Create: dialog while the draft is dirty and unsaved. Edit: leave silently (everything is saved). |
| After save | Create → the new task's detail (not the list), so the user can add what the create screen did not cover. |

That last line is the cheap fix for UXF-24: even before field parity, landing on the detail removes
the "create, then hunt for the task again" step.

---

## 6. Visual system

Every value below is an existing token or a new one with a stated job. No ad-hoc `sp`/`dp` literals
in the section files.

### Spacing (`TaskSpacing`, extended)

| Token | Value | Use |
|---|---:|---|
| `rowHeight` | 48dp | every attribute row, every sheet row |
| `sectionGap` | 8dp | between groups and between rows inside a section |
| `groupGap` | 16dp | between two groups (A→B→C→D) |
| `screenPadding` | 16dp | existing |
| `heroTopPadding` | 16dp | replaces the current 84dp |
| `cardCornerRadius` | 12dp | one radius for cards and sheets |

### Typography (theme slots, not literals)

| Element | Slot | Why |
|---|---|---|
| Title | `titleLarge` (22/28, **SemiBold via a custom `titleEmphasized`**) | follows `fontSizeScale` |
| Description | `bodyLarge` (16/24) | |
| Attribute value | `bodyLarge` | |
| Attribute label when unset | `bodyLarge` in `onSurfaceMuted` | |
| Section header | `titleSmall` (14/20, Medium) | one step above body, below title |
| Hint | `bodySmall` | |
| Footer / metadata | `bodySmall` in `onSurfaceMuted` | |

### Colour (semantic roles only)

| Role | Target |
|---|---|
| Row background | `surface` — **never** `primaryContainer` |
| Group background | `surfaceContainerLow`-equivalent, or nothing (see below) |
| Active attribute | icon in `primary` + value in `onSurface` at medium weight |
| Destructive | `error` for the delete affordance only, never a full column of them |
| Muted text | new `onSurfaceMuted` ≥ 4.5:1 on `surface` **and** on the group background |

Regarding grouping: the reference app separates cards by a gap on a darker page, ours is a light page
where `surfaceVariant` and `surface` differ by 1.23:1. On this palette **a fill is not a separator**.
Prefer: no fill at all for groups, 16dp between groups, 8dp between rows, and a single hairline
divider *inside* the planning group.

### Icons

- One set (Material). Remove the 📋 (`SubtasksSection.kt:66`).
- Priority: one glyph everywhere — `Icons.Filled.Flag`; `None`/`Medium` render **unfilled/greyed**,
  `High` filled, `Urgent` filled + `error` tint.
- Project identity: keep the colour dot (it is the only thing that survives a dark theme change);
  adopt emoji inside the **picker** only, matching the research note.

---

## 7. Accessibility

| Requirement | How |
|---|---|
| contrast | every text pair ≥ 4.5:1, enforced by a unit test that walks the 8 accents × light/dark |
| touch targets | ≥ 48dp; the row **is** the target (no invisible inflation — the defect measured in `2862-…`) |
| labels | every icon button has `contentDescription`; the checklist checkbox gets `Отметить пункт` / `Снять отметку`; the completion checkbox already has one |
| heading semantics | the title is a heading (`semantics { heading() }`) so TalkBack can jump to it |
| dynamic type | every text uses a theme slot, so `fontSizeScale` moves all of it |
| IME | the focused field is always fully visible (UXF-03) |
| no meaning by colour alone | priority has a glyph + weight; overdue has text (`Просрочено`) not just red |

---

## 8. What we deliberately do **not** adopt

| Reference behaviour | Why not |
|---|---|
| Detail as a bottom sheet that becomes a full screen | we have a real route and a back stack; the sheet→screen chrome swap gives one task two back affordances |
| Back replaced by `✓ Сохранить` while dirty | Back must always be Back; autosave makes the Save affordance unnecessary |
| Only 3 desktop-free priority levels | our 5 levels are used by the agenda selectors and the list markings; reducing them is a product change with data consequences |
| Truncating project names without an ellipsis | our names wrap in the picker; wrapping is better than silent truncation |
| Dark-only | we ship both themes |
| Explicit-commit model | verified unnecessary in the reference app too (it autosaves); a dialog on back is a tax on every task |
| Hiding *everything* behind one "more" sheet | their `+ Больше параметров` is the only entry point and sits at the very bottom; our `+ Ещё` must be one row above the footer **and** the group headers stay tappable |
