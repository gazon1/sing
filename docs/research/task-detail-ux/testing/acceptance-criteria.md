# Acceptance criteria — per workstream

Each block is the definition of done for one workstream in `implementation-plan.md`. A wave is done
when its block is fully ticked **and** the shared gates pass:

```
./check.sh
./gw :shared:jvmTest -Ptest.tags=fast
easement check
```

Nothing here replaces a human review of the screenshots — the last item of every block is one.

---

## Shared (every wave)

- [ ] No new `sp`/`dp` literal in a section file when a token exists.
- [ ] No new user-visible literal string outside `Res.string.*` (grandfathered files are listed in the
      localisation baseline and may only shrink).
- [ ] Every new `IconButton` has `contentDescription`.
- [ ] `easement check` exits 0.
- [ ] Before/after screenshots taken with `.tools/shot.sh` at 720×1600, light theme, Blue accent.
- [ ] The wave's ADR (if any) is written **before** the code and linked from `docs/decisions/`.

---

## WS2 — IME and insets (P0)

- [ ] With the IME open on `Add item…`, the whole input is above the keyboard.
- [ ] With the IME open, the app bar's top edge is below the status bar (no overlap with the clock).
- [ ] Same for `Add subtask…` and for the title/description fields.
- [ ] The create screen's behaviour is unchanged (it already passed).
- [ ] Instrumentation test asserts focused-bounds ∩ IME-inset = ∅.
- [ ] Screenshot: `13-checklist-add-keyboard-covers-field.webp` re-taken and visibly fixed.

## WS1 — stored facts become visible (P0)

- [ ] Recurrence row reads as a sentence; a test asserts no `Interval(` in the node text.
- [ ] `Крайний срок` row appears for a task with `end_date`, clears to null, and shows `Просрочен`
      in words when past.
- [ ] `Когда-нибудь` preset sets `someday`, clears the date, and the row reflects it; a task imported
      with `someday = 1` shows it on open.
- [ ] `Напоминание` row adds/edits/clears a reminder; the fire time is correct; the row pre-selects
      the existing offset; a denied permission shows the message + settings action.
- [ ] The four facts are visible on one screen for a task that has all four.

## WS3 — visual system (P1/P2)

- [ ] No `primaryContainer` background anywhere in `feature/tasks`.
- [ ] Theme test: every text/background pair used by the detail is ≥ 4.5:1 for all 8 accents × 2 modes.
- [ ] Attribute rows are 48dp; groups are 16dp apart; hero top inset is 16dp.
- [ ] On a 360×800 screen, a task with a date and a project shows its first checklist row without
      scrolling.
- [ ] Title/description/row labels use theme slots: `fontSizeScale = 1.3` grows them and nothing clips.
- [ ] Zero emoji in `feature/tasks` (arch test catches `\uXXXX` too).
- [ ] One priority glyph; `Medium` rows carry no priority mark.

## WS5 — title and bar (P1)

- [ ] A 90-character title renders on up to 3 lines with an ellipsis, and is fully readable (no hard
      clip).
- [ ] The bar shows the task title once the hero is scrolled out, and `Tasks` only as the window/a11y
      label.
- [ ] A completed task's title is visibly completed (strikethrough or equivalent) and its date row is
      replaced by `Выполнено …`; clear affordances are gone.
- [ ] Screenshot: `18-completed-task.webp` re-taken.

## WS4 — sections and `Ещё` (P1)

- [ ] A title-only task's body fits one 360×800 screen.
- [ ] `Чек-лист` / `Подзадачи` are one-line headers when empty (no hint sentence).
- [ ] `Вложения` / `Журнал` / `Связанные` / `Время` are absent when empty.
- [ ] `Состояние` is absent when unset.
- [ ] `+ Ещё` lists exactly the parameters that are not on screen, and adding one **opens its editor**
      immediately.
- [ ] Every parameter is reachable in ≤ 2 taps from the open task.
- [ ] `TaskDetailEmptyChildSectionsTest` is rewritten to the new rule and passes.
- [ ] The superseding ADR is written and linked from `2026-09-07-…`.

## WS7 — checklist and subtasks (P1/P2)

- [ ] The add row is below the items and the new item stays visible above the IME.
- [ ] A progress bar reflects `done/total` when total > 0.
- [ ] With 26 items the group shows 5 rows + `Показать все 26`; expanding does not lose scroll position.
- [ ] Delete requires a deliberate gesture and offers a 4 s undo that restores the item at its index.
- [ ] Reorder persists `sort_order` and survives reopening.
- [ ] `TASK-CHECK-01` and `TASK-SUB-01` pass on the device.

## WS6 — one scheduling sheet (P1)

- [ ] Presets (`Сегодня / Завтра / Выходные / Когда-нибудь`), the calendar and the commit are visible
      without scrolling at 360×800.
- [ ] Time and reminder are rows inside the sheet and commit together with the date.
- [ ] `Сбросить` clears date+time+reminder in one action, and the body confirms (row returns to
      `Срок`).
- [ ] Cancel and swipe-down leave the task untouched (assert in a test, not by eye).
- [ ] `02-set-due-date.yaml` / `03-due-date-cancel.yaml` updated and green.

## WS8 — create/edit parity (P1/L)

- [ ] The create screen's field set and order match the detail's (a test compares the ordered tags).
- [ ] No red validation text before any interaction.
- [ ] `Сохранить` is disabled while the title is empty and the reason is not an error.
- [ ] After saving, the app opens the new task's detail.
- [ ] Back on a dirty, never-saved draft still asks; Back on a saved draft does not.
- [ ] `13-create-full-task.yaml` passes.

## WS9 — actions reliability (P1)

- [ ] One tap on `⋮` opens the menu (test-enforced).
- [ ] One tap on each menu item acts, on the device, in 10 consecutive attempts.
- [ ] Delete still shows the undo snackbar; archive still offers undo.
- [ ] `05-archive-via-menu.yaml` / `06-delete-undo.yaml` pass.

## WS10 — one language (P1)

- [ ] No user-visible literal in `feature/tasks` outside the localisation baseline.
- [ ] Every string on the detail, the sheets and the create screen comes from resources.
- [ ] Switching the device language changes the screen (one manual pass, one screenshot).

## WS11 — project picker (P1)

- [ ] The acceptance criteria of `docs/research/2862-project-navigation-comparison.md` are met
      (48dp rows, 24dp indent, emoji, single-line name, search, "Add project", filled selection).
- [ ] `ProjectPickerDismissTest` still passes (shared with Desktop).

## WS12 — footer and accessibility (P3)

- [ ] `Создана … · изменена …` footer present, muted, one line.
- [ ] Checklist checkbox has a dynamic label (`Отметить пункт` / `Снять отметку`).
- [ ] The title is a heading in the semantics tree.
- [ ] A TalkBack pass over the three fixture tasks reads: title → description → date → reminder →
      project → priority → checklist count → subtasks count.

---

## Screenshot set to re-record when the work lands

| Now (before) | After (expected) |
|---|---|
| `02-task-detail-minimal-top.webp` | no blue blocks, 48dp rows, title 3-line capable |
| `03-task-detail-empty-sections.webp` | one-line headers, no hint sentences, no `Interval(…)` |
| `04-task-detail-bottom-empty.webp` | footer instead of four empty cards |
| `13-checklist-add-keyboard-covers-field.webp` | input visible above the IME |
| `14-checklist-with-item.webp` | add row below, progress bar, no permanent red ✕ |
| `16-rich-checklist-26-items.webp` | collapsed with `Показать все` |
| `18-completed-task.webp` | completed styling, wrapped title |
| `19-create-screen.webp` | no pristine error, same fields as the detail |
| `07-date-sheet-collapsed.webp` | actions visible without scrolling |
| `10-state-sheet.webp` | state row absent until set |
