# UX findings — evidence, impact, proposal, priority

One block per finding. Every finding is something **observed on the device in this session**, with a
screenshot and, where the cause is visible in code, a `file:line`.

Priority scale:

- **P0** — the screen is unusable or data is lost / a stored fact cannot be seen or set.
- **P1** — a core scenario is materially worse than it should be.
- **P2** — visible quality and comfort.
- **P3** — cosmetic.

Complexity: **S** ≈ one file, **M** ≈ one component + tests, **L** ≈ crosses VM/state/navigation.

---

## UXF-01 — Recurrence is rendered as a Kotlin `toString()`

- **Scenario:** open any repeating task.
- **Now:** the `Repeats` card prints
  `Interval(base=FROM_DUE, amount=3, unit=DAY, termination=RecurrenceTermination(endDate=2025-01-29))`.
- **SingularityApp:** a `Перейти к шаблону` row (a plain row, rule not inspected).
- **Evidence:** `singularity-todo/screenshots/12-recurrence-raw-tostring.webp`.
- **Impact:** every repeating task (80 in this profile, and they are the tasks a user opens most)
  shows an unreadable string where a sentence should be. It also reads as a bug in a demo — this is
  the single most damaging screenshot in the app.
- **Proposal:** format the rule through a pure formatter, mirroring the existing
  `RecurrenceFormatters.kt` helpers: `Каждые 3 дня, с 12 ноя 2026, до 29 янв 2025`. Never call
  `toString()` on a domain object from a composable.
- **Expected result:** the row reads as a sentence, and the same formatter is used by the picker and
  by the list meta line.
- **Priority:** **P0** · **Complexity:** S · **Depends on:** nothing.

---

## UXF-02 — A reminder cannot be set on a task at all

- **Scenario:** open a task and try to add a reminder.
- **Now:** there is no row, no sheet, no icon. `TaskDetailUi.reminders` is populated
  (`TaskDetailUiState.kt:51`) and read by nothing; `TaskDetailIntent.Domain.SetReminder` is routed
  (`TaskDetailCoordinator.kt:563`) and dispatched by nothing; `TaskEditorSheet` has no `Reminder`
  variant (`TaskEditorSheet.kt:6-38`). The route KDoc still claims
  "the task's detail screen is the only place that can set a reminder about the task"
  (`TasksRoute.kt:32-34`).
- **SingularityApp:** a `Напомнить` row inside the scheduling card, filled by the same sheet that
  owns the date.
- **Evidence:** `singularity-todo/screenshots/02…04` (no reminder row anywhere);
  `singularity-app/screenshots/03-task-detail-full.webp`.
- **Impact:** the feature is, from the user's point of view, absent. The slot, the repository and the
  alarm scheduler are all written and tested (`TaskRemindersSlotTest`), so this is a wiring +
  one-row + one-sheet job, not a feature build.
- **Proposal:** add a `Напоминание` row under the due date that opens a reminder sheet
  (offsets 0/5/10/15/30/60 min + custom), pre-selecting the task's current reminder;
  the row shows the reminder time and a clear ✕.
- **Expected result:** a reminder can be added, changed and cleared; the notification fires.
- **Priority:** **P0** · **Complexity:** M · **Depends on:** UXF-16 (sheet host unification) if done
  together.

---

## UXF-03 — The keyboard hides the field the user is typing into

- **Scenario:** add a checklist item (or a subtask).
- **Now:** the input is at y≈1072px; the IME starts at y≈945px. The field is behind the keyboard and
  the content does not scroll to reveal it. With the IME open the top app bar also slides under the
  status bar (`Tasks` overlaps the clock).
- **SingularityApp:** `+` appends a focused row inside the card; the row stays above the keyboard.
- **Evidence:** `13-checklist-add-keyboard-covers-field.webp`, `22-title-edit-quick-actions.webp`;
  contrast `singularity-app/10-checklist-inline-add.webp`.
- **Impact:** the user types blind into the field that creates the item — and the field is *above* the
  list, so even scrolling does not help because there is nothing below it to scroll into view.
- **Proposal:** the detail's Android host must apply `imePadding()` (and status-bar insets) to the
  scroll container, and the focused field must be brought into view (`BringIntoViewRequester`), the
  same way the create screen already behaves.
- **Expected result:** with the IME open, the focused input is fully visible and the app bar does not
  overlap the status bar.
- **Priority:** **P0** · **Complexity:** M · **Depends on:** nothing. A Compose UI test can assert
  the focused bounds are above the IME window (`WindowInsets.isImeVisible`).

---

## UXF-04 — Six empty sections are always drawn

- **Scenario:** open a task that has a title, a date and a project.
- **Now:** `Related tasks`, `Checklist`, `Subtasks`, `Attachments`, `Time`, `Logbook` each render a
  card with a hint sentence and either an input or a link. That is ~55% of a two-screen scroll
  (`03`, `04`).
- **SingularityApp:** the detail lists only parameters the user added; the rest live behind
  `Больше параметров` with a pin per row.
- **Evidence:** `03-task-detail-empty-sections.webp`, `04-task-detail-bottom-empty.webp` vs
  `singularity-app/09-more-parameters.webp`.
- **Impact:** the screen is longer than the content, the real content (checklist with 26 items) is
  pushed down, and every visit re-reads six sentences that say "nothing here".
- **Proposal:** two-tier rule, not a blanket hide (see `implementation/target-ux.md` §3):
  **always** show `Checklist` and `Subtasks` as a single one-line row with a `+` (no hint sentence);
  show `Attachments`, `Time`, `Logbook`, `Related tasks` **only when non-empty**; put everything
  else behind one `+ Ещё` row that opens a parameter picker.
- **Expected result:** a task with just a title is ≤ 1 screen; nothing is unreachable, because the
  entry point to add each thing is still on screen.
- **Priority:** **P1** · **Complexity:** M · **Depends on:** UXF-04 decisions affect the tests in
  `TaskDetailEmptyChildSectionsTest`.

---

## UXF-05 — Create and edit are two different contracts

- **Scenario:** create a task, then edit the created task.
- **Now:**

  | | Detail | Create |
  |---|---|---|
  | commit | autosave (300 ms) | explicit `Сохранить` |
  | validation | silent revert on empty title | `Title is required` shown immediately |
  | Back | leaves, flushes | `Discard changes?` |
  | fields | 13 + 6 empty sections | 8 |
  | project row | 5th row | appears only after the title is non-empty |

- **SingularityApp:** one model — autosave, with `✓ Сохранить` as a finishing affordance.
- **Evidence:** `19-create-screen.webp`, `20-create-typing-keyboard.webp`, `21-create-discard-dialog.webp`
  vs `02-task-detail-minimal-top.webp`, `22-title-edit-quick-actions.webp`.
- **Impact:** the user learns two sets of rules for one object. The Create screen is also the only
  place that can lose text (its Save is explicit), while the Detail is the only place that can do
  most of the work.
- **Proposal:** keep the two *surfaces* (a full screen for creation is right on a phone) but unify
  the **contract**: autosave the draft, keep `Сохранить` as "done", replace the pristine error with
  an error only after a save attempt or a blur, and give both surfaces the same field set.
- **Expected result:** a user who has used either screen knows the other.
- **Priority:** **P1** · **Complexity:** L (crosses `TaskCreateViewModel`, `TaskDetailCoordinator`,
  navigation and tests) · **Depends on:** UXF-04, UXF-12, UXF-24.

---

## UXF-06 — The title is clipped, not wrapped or ellipsised

- **Scenario:** open a task with a long title (the imported profile is full of them).
- **Now:** `SingleLine` `BasicTextField` clips at the container edge:
  `Отмамамыларамливаю к` — no ellipsis, no second line (`TaskTitleRow.kt:145`).
- **SingularityApp:** the hero title wraps to 2–3 lines.
- **Evidence:** `18-completed-task.webp`.
- **Impact:** the primary identifier of the screen is unreadable for a large share of real tasks, and
  there is no tooltip or other place to read it.
- **Proposal:** allow up to 3 lines in the hero (`lineLimits = MultiLine(maxHeightInLines = 3)`),
  ellipsis beyond, and keep the single-line behaviour only for the list row where height matters.
- **Expected result:** a 90-character title is fully readable on the detail.
- **Priority:** **P1** · **Complexity:** S · **Depends on:** nothing.

---

## UXF-07 — The app bar names the feature, not the task

- **Scenario:** open a task; open a subtask from it.
- **Now:** the bar says `Tasks` on every depth. Nothing on screen attributes the content to a task
  except the field itself.
- **SingularityApp:** no title either — but the sheet keeps the list behind it, so the origin is
  always visible.
- **Evidence:** every `singularity-todo` screenshot.
- **Impact:** with a stack of details the user cannot tell where they are; a11y users hear "Tasks"
  as the screen title.
- **Proposal:** show the task title in the bar as a small, ellipsised label while the hero is scrolled
  out of view (a collapsing title); keep `Tasks` only as the a11y/window label.
- **Expected result:** the bar identifies the object once the hero is off screen.
- **Priority:** **P1** · **Complexity:** S · **Depends on:** UXF-06 (shares the title-length rule).

---

## UXF-08 — "Filled" attributes are painted `primaryContainer` with `onSurface` text (3.67:1)

- **Scenario:** open a task that has a date and a project.
- **Now:** the state row and the project row become solid `#46709E` blocks with near-black text.
  Measured contrast **3.67:1**, below WCAG AA for 16sp (`TaskAttributeCard.kt:43-44` pairs
  `primaryContainer` with `onSurface`).
- **SingularityApp:** every row is the same neutral card; the accent appears on the icon only.
- **Evidence:** `02-task-detail-minimal-top.webp`; numbers in `comparison/visual-analysis.md`.
- **Impact:** two of the most-used rows are the hardest to read, they shout louder than the title,
  and the fill has no consistent meaning (some filled rows are cards, some are bare).
- **Proposal:** drop the filled variant. Mark "set" with the icon tint (already implemented) plus a
  medium weight on the value, as `TaskAttributeCard` already does for `isActive`.
- **Expected result:** every attribute row has the same surface; contrast ≥ 4.5:1; the title is again
  the loudest element on screen.
- **Priority:** **P1** · **Complexity:** S · **Depends on:** design-token decision (UXF-09).

---

## UXF-09 — Secondary text fails contrast

- **Scenario:** any empty description, any hint sentence.
- **Now:** placeholder `#AEB0B6` on `#F8F9FF` = **2.06:1**; the empty-section hint uses
  `bodySmall` on `surfaceVariant` (≈ 3.4:1 measured).
- **SingularityApp:** muted text `#6B7180` on `#0A1329` = 3.78:1 — also below AA, so this is a
  shared problem, not a reason to keep ours.
- **Evidence:** measurements in `visual-analysis.md`.
- **Impact:** hints and placeholders are the copy that teaches the screen; at 2.06:1 they are
  decoration for anyone with reduced contrast sensitivity, and unreadable in sunlight.
- **Proposal:** introduce one `onSurfaceMuted` token at ≥ 4.5:1 on `surface` and on
  `surfaceVariant`, and use it for placeholders and hints.
- **Expected result:** every text pair in the detail passes AA (a unit test can compute the ratios
  from the theme).
- **Priority:** **P1** · **Complexity:** S · **Depends on:** UXF-08 (same token work).

---

## UXF-10 — Vertical rhythm is 1.6× the reference

- **Scenario:** reach the checklist on a task with a date and a project.
- **Now:** attribute rows sit on a **76dp pitch** (priority text at y=720, estimate 872, date 1024,
  project 1176); the hero starts **84dp** below the app bar; a bare row is 52dp of card.
- **SingularityApp:** 48–50dp rows, **8dp** gaps, hero immediately under the bar.
- **Evidence:** measured in `environment.md` + `visual-analysis.md`; `02` vs
  `singularity-app/02-task-detail-top.webp`.
- **Impact:** ~1 extra screen of scrolling per task; the checklist — the thing most tasks are opened
  for — is below the fold on every task.
- **Proposal:** 48dp rows, 8dp section gap, 16dp top inset; keep the 48dp touch target.
- **Expected result:** on a 360×800 screen the first checklist row is visible without scrolling for a
  task with a date and a project.
- **Priority:** **P2** · **Complexity:** S · **Depends on:** UXF-08 (the fill change makes the
  spacing change safe to judge).

---

## UXF-11 — The bottom navigation stays on the detail and clips content

- **Scenario:** scroll any task to the end.
- **Now:** `Inbox / Today / Upcoming / Calendar / Menu` remain visible and the last card (Logbook) is
  cut by the bar (`04`); the bar is 80dp tall.
- **SingularityApp:** the detail covers the list; the bottom bar is not part of the detail.
- **Evidence:** `04-task-detail-bottom-empty.webp`.
- **Impact:** content is literally unreachable at the bottom edge in some scroll positions, and a tab
  tap from inside a task changes the whole context.
- **Proposal:** hide the shell bar while a `TasksRoute.Detail` is on top (the graph already knows the
  start route), or make the detail a full-screen route in its own graph.
- **Expected result:** the detail's last card is fully visible and scrolling to the end reaches the
  bottom inset, not the nav bar.
- **Priority:** **P1** · **Complexity:** M · **Depends on:** navigation ownership
  (`2026-10-05-one-task-detail-screen…`).

---

## UXF-12 — "Title is required" is shown before the user has typed anything

- **Scenario:** open Create.
- **Now:** the red error is on the form from the first frame (`19`); it disappears once the title is
  non-empty, so it is effectively a disabled-button explanation rendered as an error.
- **SingularityApp:** the send button is simply inactive.
- **Evidence:** `19-create-screen.webp`.
- **Impact:** the first thing a user sees when creating a task is a complaint.
- **Proposal:** show the error only after a save attempt or a focus loss on a non-empty field; until
  then keep `Сохранить` disabled without a message.
- **Expected result:** a pristine Create screen has no red text.
- **Priority:** **P1** · **Complexity:** S · **Depends on:** UXF-05.

---

## UXF-13 — The overflow button sometimes ignores the first tap

- **Scenario:** open the detail menu.
- **Now:** on at least four attempts the first tap on `⋮` did nothing and the second opened the menu;
  the menu's items are `clickable=false` text nodes and one tap on `Удалить` dismissed the menu
  without acting.
- **SingularityApp:** `⋮` opened on the first tap every time.
- **Evidence:** observed repeatedly; the consequence is visible in `23-delete-undo-snackbar.webp`
  (delete was only reachable after retries).
- **Impact:** Archive and Delete are the only actions in the menu — an unreliable entry point to
  both. A destructive action that sometimes does nothing is also a trap: users learn to tap twice,
  and the second tap can land on `Архивировать`.
- **Proposal:** reproduce with a Compose test that taps the overflow once and asserts one menu; check
  whether the bar's "publish actions to the Android shell" path
  (`TaskDetailTopBar.kt:35-41`) consumes the first press or re-lays out the icon. Fix the hit area,
  and move Archive/Delete into the bottom action bar or a sheet with ≥ 48dp rows.
- **Expected result:** one tap opens the menu; a tap on an item always acts.
- **Priority:** **P1** (P0 if the cause turns out to be a lost click on a destructive item) ·
  **Complexity:** S–M · **Depends on:** reproducing in a test first.

---

## UXF-14 — Priority has two glyphs and two colour languages

- **Scenario:** compare a task row with its detail.
- **Now:** the list marks priority with a **★** (yellow for `Medium`, red for `High`), the detail with
  a **flag**; the sheet shows five flag colours where `High` and `Urgent` are the **same red**.
- **SingularityApp:** ★ in the list, ⓘ in the sheet, with the level named in words.
- **Evidence:** `01-today-list.webp`, `02`, `06`.
- **Impact:** 11 301 tasks are `Medium`, so nearly every row carries a star that means "default"; the
  mark that should draw attention is the most common thing on screen. `High` vs `Urgent` is
  indistinguishable at a glance.
- **Proposal:** one glyph for priority everywhere (flag), no mark for `None`/`Medium` (the default),
  distinct colour for `Urgent` (e.g. a filled flag vs an outline).
- **Expected result:** only tasks that were deliberately prioritised stand out in the list.
- **Priority:** **P2** · **Complexity:** S · **Depends on:** `TaskMarkingsRow` / `PriorityIndicator`.

---

## UXF-15 — `Next up` / `In progress` are unexplained jargon

- **Scenario:** open the state sheet.
- **Now:** three options with no description: `No state`, `Next up`, `In progress`. A completed task
  keeps the row, disabled.
- **SingularityApp:** no such concept.
- **Evidence:** `10-state-sheet.webp`.
- **Impact:** a row that most users will not understand occupies 52dp at the top of every task, and
  the app has no copy that explains what the state does to the agenda.
- **Proposal:** either (a) hide the row until a state is set, and offer the sheet from the `+ Ещё`
  picker, with one explanatory line per option, or (b) drop the concept from the detail and keep it
  in the list's context menu.
- **Expected result:** no unexplained control above the priority row.
- **Priority:** **P2** · **Complexity:** S · **Depends on:** product decision (does workflow state
  earn a row?).

---

## UXF-16 — The date sheet opens with its own actions below the fold

- **Scenario:** change a due date.
- **Now:** the sheet opens at partial height: the calendar is cut and **no Clear / Cancel / OK is
  visible**; a scroll is required to commit (`07` → `08`).
- **SingularityApp:** one sheet with presets, calendar, reset row and two buttons, all visible.
- **Evidence:** `07-date-sheet-collapsed.webp`, `08-date-sheet-expanded.webp`.
- **Impact:** an extra gesture on a frequent operation, and the commit affordance is invisible on
  first sight — a user may think the sheet is broken.
- **Proposal:** open the sheet at the height of its content (`skipPartiallyExpanded = true`) and size
  the calendar so the action row is on screen; add presets (`Сегодня / Завтра / Выходные`) above the
  calendar; move `Clear` into the calendar header as an icon.
- **Expected result:** date, presets and commit are visible without scrolling on a 360×800 screen.
- **Priority:** **P1** · **Complexity:** M · **Depends on:** `DatePickerSheet`.

---

## UXF-17 — The project picker is a flat list of 132 items

- **Scenario:** assign a project.
- **Now:** A→Z, 52dp rows, grey folder for every project, no search, no hierarchy, no emoji, no
  "create project", no highlighted current project in view.
- **SingularityApp:** search field, 48dp rows, 24dp indent for sub-projects, emoji, filled selected
  row, current project first.
- **Evidence:** `09-project-picker.webp` vs `singularity-app/05-project-picker.webp`; the same defects
  are already measured in `docs/research/2862-project-navigation-comparison.md`.
- **Impact:** with this profile a project is up to 132 rows away; the user cannot tell which one is
  selected without scrolling.
- **Proposal:** adopt the P0/P1 items of that research note (tree order, indent, emoji, 48dp,
  single-line name, "Add project", search). No new research needed.
- **Expected result:** any project is reachable in ≤ 10 rows of scrolling or by typing 3 characters.
- **Priority:** **P1** · **Complexity:** M · **Depends on:** `ProjectTreeBuilder` (exists, tested).

---

## UXF-18 — The checklist falls short of the pattern it claims to follow

- **Scenario:** work with a 26-item checklist.
- **Now:** no progress bar (only `Checklist (4/26)` text), no collapse, no reorder, the add field is
  **above** the items, and each row carries a permanent red ✕ with no undo.
- **SingularityApp:** collapsible header, counter with a word, inline add at the end, drag handle,
  swipe to delete.
- **Evidence:** `16-rich-checklist-26-items.webp`, `14-checklist-with-item.webp`, `13` vs
  `singularity-app/10-checklist-inline-add.webp`.
- **Impact:** the most-used sub-feature of the detail is the least finished one: 26 rows push
  everything else off screen, and the delete affordance competes with the content.
- **Proposal:** (a) add a `LinearProgressIndicator` under the header when total > 0; (b) collapse the
  card when total > 5 with "Показать все"; (c) move the add row **below** the items; (d) replace the
  ✕ with swipe-to-delete + undo snackbar (the VM already has `UndoDeleteChecklistItem`);
  (e) reorder with a drag handle, persisting `sort_order` (already a column).
- **Expected result:** a 26-item checklist does not dominate the screen and no item can be lost by a
  stray tap.
- **Priority:** **P1** (a, c, d) · **P2** (b, e) · **Complexity:** M · **Depends on:** `ChecklistItemRow`,
  `TaskDetailExtraSections`.

---

## UXF-19 — Only one action in the screen is undoable

- **Scenario:** delete a checklist item / clear a date / clear a project.
- **Now:** checklist delete is immediate and silent; clear actions are ✕ with no undo; task delete is
  the only action with a snackbar (and the VM can already undo tags, checklist items, subtasks and
  archives — the events exist in `TaskDetailUiEvent.kt`).
- **SingularityApp:** none of these are undoable either (so this is not a "they do it better", it is
  a gap in both).
- **Evidence:** `14` → `03` (item vanishes), `23-delete-undo-snackbar.webp` (the one that works).
- **Impact:** a mis-tap destroys data with no recovery path; users become careful in a way that makes
  the app feel fragile.
- **Proposal:** route checklist/subtask delete and clearing a field through the existing undo events
  and the existing `NotificationHost` snackbar (4 s, `Undo`).
- **Expected result:** every destructive action in the detail is reversible for 4 s.
- **Priority:** **P2** · **Complexity:** S (the events already exist) · **Depends on:** UXF-18(d).

---

## UXF-20 — Two stored facts have no surface: deadline and `someday`

- **Scenario:** open a task that has `end_date` (150 tasks) or `someday = 1` (310 tasks).
- **Now:** no row, no chip, no mention. The recurrence row says nothing about either.
- **SingularityApp:** `Крайний срок` row and a `Когда-нибудь` date preset.
- **Evidence:** `test-data.md` counts; `03` and `04` show the whole scroll and neither field.
- **Impact:** a fact the user set in the previous app is invisible in this one — the worst kind of
  data loss from the user's point of view, because the row is still in the database.
- **Proposal:** add a `Крайний срок` row under the date (same picker, distinct icon, and a "просрочен"
  state when it passes); add `Когда-нибудь` as a preset in the date sheet, reflecting `someday`.
- **Expected result:** all four scheduling facts (`due`, `deadline`, `reminder`, `someday`) are
  visible when set and settable when not.
- **Priority:** **P1** · **Complexity:** M · **Depends on:** UXF-16 (the date sheet hosts the preset).

---

## UXF-21 — The screen speaks two languages

- **Scenario:** read the detail top to bottom.
- **Now:** `No state`, `Medium priority`, `Add tags`, `Not pinned`, `Related tasks`, `Checklist`,
  `Subtasks`, `Attachments`, `Time`, `Logbook`, `Clear / Cancel / OK`, `Saved` next to
  `Введите описание задачи...`, `Добавить оценку`, `Добавить дату`, `Чт, 12 ноя 2026`,
  `Архивировать`, `Удалить`.
- **SingularityApp:** Russian throughout (the app follows the account's language).
- **Evidence:** `02`, `03`, `05`, `13`, `19`.
- **Impact:** the app looks unfinished, and several controls are only meaningful in one language
  (`Clear` vs `Сбросить дату` in the very same sheet, `05`).
- **Proposal:** move every literal into `shared/src/commonMain/composeResources/values*/strings.xml`
  (the project already uses `Res.string.*` for parts of this screen), and add a check that fails on
  a user-visible literal in `feature/tasks`.
- **Expected result:** one language on screen, switchable.
- **Priority:** **P1** · **Complexity:** M (breadth, not depth) · **Depends on:** nothing.

---

## UXF-22 — A purple "Quick actions" card appears on new tasks

- **Scenario:** open a task that was just created.
- **Now:** a `tertiaryContainer` card (purple, the only purple in the app) titled `Quick actions`
  with one chip `Ask AI` is inserted between the description and the state row
  (`FirstRunSection.kt`, shown when `FirstRun.Offer`).
- **SingularityApp:** nothing equivalent.
- **Evidence:** `22-title-edit-quick-actions.webp`, `24-ai-actions-sheet.webp`.
- **Impact:** it changes the layout of the screen the first time a user creates a task, in a colour
  that belongs to no other element, and offers one action whose label does not say what it will do.
- **Proposal:** either remove it (the sparkles button in the bar already opens the same sheet) or
  restyle it as a normal section card inside the hero, with the label `Что сделать с задачей?`.
- **Expected result:** the detail of a new task looks like the detail of an old one.
- **Priority:** **P2** · **Complexity:** S · **Depends on:** decision on the AI surface.

---

## UXF-23 — A completed task looks like an open one

- **Scenario:** open a completed task.
- **Now:** only the checkbox changes (filled); the title keeps weight and colour, the rows keep their
  ✕ clear affordances, nothing says "done" in text.
- **SingularityApp:** not exercised.
- **Evidence:** `18-completed-task.webp`.
- **Impact:** a user who opens a task from a search result cannot tell it is finished without looking
  at the checkbox.
- **Proposal:** strike through or dim the title, replace the date row with `Выполнено 12 ноя 2026`,
  and hide the clear affordances that no longer make sense.
- **Expected result:** the state is readable at a glance and from a screen reader.
- **Priority:** **P2** · **Complexity:** S · **Depends on:** `TaskTitleRow`.

---

## UXF-24 — Create cannot express what the detail can, and grows a row mid-typing

- **Scenario:** create a task with tags, or with a repeat rule.
- **Now:** Create offers title, description, checklist, attachments, priority, date, start date,
  project. Tags, recurrence, reminder and estimate are missing; the project row only appears after
  the title is non-empty, so the form reflows under the user's eyes (`19` → `20`).
- **SingularityApp:** the composer carries date, time, project and tags; everything else is one
  `⛶` away.
- **Evidence:** `19-create-screen.webp`, `20-create-typing-keyboard.webp`.
- **Impact:** the user must create a lesser task and immediately reopen it — the exact round trip the
  `2026-09-05-task-editor-refactor` ADR tried to remove.
- **Proposal:** keep the field set identical to the detail (`TaskEditorContent` already renders both
  from one model — the divergence is in the call sites, not the component); never add a row on the
  first keystroke.
- **Expected result:** a task can be created complete, in one pass, with the same fields in the same
  order as the detail.
- **Priority:** **P1** · **Complexity:** M (the renderer already supports it) · **Depends on:** UXF-05.

---

## UXF-25 — No provenance: created/updated are never shown

- **Scenario:** any task.
- **Now:** `created_at`/`updated_at` exist and are not rendered anywhere.
- **SingularityApp:** `Создана 03.10.2026, 14:10 | Изменена 03.10.2026, 14:10` at the very bottom.
- **Evidence:** `singularity-app/03-task-detail-full.webp`.
- **Impact:** low, but it is free, and it is the only place that answers "when did I write this?"
- **Proposal:** one `bodySmall` footer line, muted, after the last section.
- **Expected result:** the footer exists and does not add a section card.
- **Priority:** **P3** · **Complexity:** S · **Depends on:** UXF-04 (the bottom of the screen is being
  reorganised anyway).

---

# Things that look wrong but are not defects — do not "fix" blindly

| Observation | Why it should stay |
|---|---|
| Delete has no confirmation, only `Undo` | Correct trade for a reversible action; a confirm on every delete is worse. The DoD's `ConfirmDeleteSheet` requirement is the thing to revisit, not the behaviour. |
| Empty-title keystroke silently reverts the field | A blank task cannot exist; a toast on every backspace would be worse. Only the *first* save needs to be silent. |
| The list and the detail both toggle completion | Two ways to do one thing, but both are one tap and both are where the user's hand already is. |
| Debounced autosave with no snackbar | Correct: autosaves are not requested operations. The `Saved` pill is the right volume. |
| `Tasks` in the app bar on Create | Create has no object yet; a feature label is defensible **there** and wrong on the detail (UXF-07). |
