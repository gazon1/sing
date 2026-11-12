# SingularityApp — screen and state inventory (Android 10.23.0)

Screenshots in `screenshots/`, same device and session as the other app
(`0O65321F32204DD5`, 720×1600 px, density 320 → **1dp = 2px**). SingularityApp is Flutter with
semantics off, so **no accessibility tree exists**; all geometry below is read from pixels. Layout
numbers (48dp rows, 8dp gaps, card bounds) are exact because they are card edges in the image; font
sizes are *inferred from ink height* and marked "≈".

Unlike Singularity Todo, this app is **dark-only** and draws task detail as a **bottom sheet that
grows into a full screen**.

---

## 0. Screenshot index

| File | What it shows |
|---|---|
| `01-today-list.webp` | Today list — row anatomy, project colour bars, FAB |
| `02-task-detail-top.webp` | Task detail as a half-height sheet: hero, description, checklist, project, priority, date |
| `03-task-detail-full.webp` | The same detail expanded to full screen: the whole parameter list + created/updated footer |
| `04-description-editor.webp` | Description editor — full sheet, rich-text toolbar above the IME |
| `05-project-picker.webp` | "Перенести в" — search field, project list, selected row |
| `06-priority-sheet.webp` | Priority — 3 levels + "Прикрепить задачу" |
| `07-date-time-sheet.webp` | "Дата и время" — presets, calendar, reset row, time row, Отменить/Сохранить |
| `08-time-sheet-nested.webp` | Time — clock dial nested **over** the date sheet |
| `09-more-parameters.webp` | "Больше параметров" — the parameter picker, one pin per row |
| `10-checklist-inline-add.webp` | `+` on the checklist adds an inline focused row; bar switches to `✓ Сохранить` |
| `11-quick-add-composer.webp` | Quick-add composer above the IME with date/time chips and attribute icons |

---

> Screenshots are stored as **WebP** (`*.png` is gitignored repo-wide; the existing research
> assets use WebP for the same reason). Two file names exist in both app folders
> (`01-today-list`, `06-priority-sheet`); outside an app folder a bare file name means
> **Singularity Todo**, and a `singularity-app/…` prefix means the reference app.

## 1. Today list (`01`)

| Element | Measured |
|---|---|
| row | content-dependent; up to 3 title lines |
| project colour bar | 6px = 3dp, full-height, left edge |
| checkbox | 40px = 20dp square, 1.5px border, rounded 4dp |
| priority ★ | 28px ≈ 14dp, grey for Medium, blue for High |
| title | ≈ 26px ink ≈ 17sp, up to 3 lines |
| meta line | `⟳ Вс, 01 нояб 2026 / Финансы` — recurrence glyph, date, `/`, project — one muted line |
| overdue/repeat note | `⟳ 2 просроченных повтора` in orange, only when true |
| FAB | 96px = 48dp circle with a ~2px ring |
| bottom bar | `Меню / Сегодня / Планы`, 160px = 80dp |

Two behaviours worth noting:

- The task **without a checkbox** at the top (`Проверить % по кредиткам`) is a *project/section
  header*, not a task — it carries its own chevron and a status line (`Ожидание / Финансы`).
- Long titles wrap; the meta line truncates **with `…`** (`Read papers in re…`).

---

## 2. Task detail — the sheet that becomes a screen

| State | Chrome | Evidence |
|---|---|---|
| opens at ~55% height | drag handle at top, `⋮` at top-right, list still visible behind | `02` |
| after scrolling | **expands to full screen**; the handle is replaced by `‹ Назад`, `⋮` stays | `03` |
| edited (dirty) | the top-left control becomes **`✓ Сохранить`** (blue) — Back is gone while dirty | `10` |

This is one component with two chromes, and the chrome changes based on both scroll position and
dirty state. It is worth copying the *idea* (a task opens without leaving the list) but not
blindly: the disappearing Back button is a discoverability problem of its own.

### 2.1 Anatomy, top to bottom (`03`)

| # | Block | Empty state | Filled state |
|---|---|---|---|
| 1 | Hero: checkbox + title + **project name as subtitle** | — | `Снять 50к с альфы банкомана` / `Финансы` |
| 2 | Description card | `Введите описание задачи...` + `›` | first line + `›` |
| 3 | Checklist card | `Чек-лист` + `+` | `Чек-лист (0/1 выполнено)` + `+` + `˅` |
| 4 | Project card | `Без проекта` | emoji + name |
| 5 | Priority card | `Приоритет` | `Средний приоритет` |
| 6 | **Scheduling card** (one card, hairline dividers) | `Напомнить`, `Крайний срок`, `Перейти к шаблону` | `Вс, 01 нояб 2026` + ✕ / `20:00` / `Крайний срок` |
| 7 | Subtasks card | `Подзадачи` + `+` | rows |
| 8 | `+ Больше параметров` | always present | — |
| 9 | Footer | — | `Создана 03.10.2026, 14:10 \| Изменена 03.10.2026, 14:10` |

Measured geometry:

| | px | dp |
|---|---:|---:|
| card height (description, checklist) | 96 | **48** |
| card height (project, priority) | 100 | **50** |
| gap between cards | 16 | **8** |
| card corner radius | ≈24 | ≈12 |
| sheet/page background | `#080D21` | — |
| card fill | `#0A1329` | — |
| selected row in a picker | `#101F3E` + 1px border | — |
| horizontal card inset | 32 | 16 |

**Every card is the same neutral fill.** Nothing is painted with the accent colour when a value is
set — the accent appears only on icons that are *active* (the repeat glyph in `03` is blue because a
rule exists) and on the primary buttons.

---

## 3. Description (`04`)

A separate **full-height sheet** with:
`✕` · centred title `Описание` · `✓`, then a document area and, above the IME, a toolbar:
`+` · image · `TT` · bullet list · numbered list · trash · undo · keyboard.

Placeholder: `Добавляйте заметки, изображения, таблицы и ссылки...`
So the description is a rich document, not a plain text field, and it commits with `✓`.

---

## 4. Checklist (`10`)

- `+` does **not** open a sheet: it appends a focused row inside the card and opens the IME; the row
  and the keyboard never overlap, and the content above the keyboard stays visible.
- Header switches to `Чек-лист (0/1 выполнено)` (count + word) and a collapse chevron appears.
- An item row carries a **drag handle** on the right (`≡`) — reorder is a real affordance.
- Swipe left deletes an item immediately, no confirmation, **no undo** (verified: my probe item
  disappeared and `Чек-лист` reverted to its empty label).
- The header loses its counter when the checklist becomes empty again — same as ours.

---

## 5. Project picker (`05`)

- `✕` · `Перенести в` · search field (`Начать поиск по проектам`) · list.
- Rows: emoji or coloured progress ring, name, **48dp**, selected row filled.
- Tapping a row **applies and closes immediately** (verified — my tap moved the task and the sheet
  closed; there is no undo snackbar).
- Long names are **cut without an ellipsis** (`лазернуая коагуляция сетчатк`), which is worse than
  our wrap.
- The list is re-ordered so the current project appears first (observed: `Финансы` moved to the top
  after selection).

---

## 6. Priority (`06`)

`✕` · `Приоритет` · `✓`, then **3 rows**: `Высокий` (red ⓘ), `Средний` (grey ⓘ), `Низкий`
(grey ⓘ) with radios, then a 4th row `Прикрепить задачу` (pin + checkbox).

- Confirm with `✓`; unlike ours, tapping a level does **not** close the sheet.
- Only 3 levels. This profile has almost everything at `Medium`, so the level list is short by design.

---

## 7. Date & time (`07`, `08`)

One sheet for the whole scheduling decision:

1. `✕` · `Дата и время` · `✓`
2. **4 icon presets**: `Сегодня` (★), `Вечером` (moon), `Завтра` (sun), `Когда-нибудь` (arc)
3. Month header with `‹ ›`, ≈22sp
4. Calendar, 7 columns, 42dp rows; selected day = filled blue circle; today = blue text
5. `Сбросить дату` row with `✕`
6. `Время` row (opens `08`)
7. `Напомнить` row
8. Two buttons: `Отменить` (dark) and `Сохранить` (blue)

`08`: the time picker is a **second sheet stacked over the first**, with a clock dial, hour/minute
cards (active one outlined in blue), and the same two buttons. A small keyboard glyph with a dot
switches to manual entry.

So: date, time and reminder live in one flow with explicit commit, and "someday" is a first-class
preset next to Today/Tomorrow.

---

## 8. More parameters (`09`)

A sheet listing the parameters that are *not* currently on the task: `Чек-лист`, `Запустить
Помодоро`, `Приоритет`, `Дата и время`, `Напомнить`, `Крайний срок`, `Место`, `Новая подзадача`,
`Теги`. Every row has a **pin** on the right.

Reading: the detail shows only what the user asked for; anything else is added from here, and the
pin decides whether it stays visible. This is the single most important structural difference from
our screen, where six empty sections are always drawn.

*(What a pin does when toggled was not exercised — the sheet was opened and read only. Recorded as a
limitation rather than guessed.)*

---

## 9. Create (`11`)

Not a screen — a composer docked above the IME:

```
[ Новая задача                                    ⛶ ]
[ Сегодня × ] [ Время ]
[ 📅 ] [ 🏷 ] [ ↗ Без проекта ] [ ⋯ ]        [ ➤ ]
```

- One text field, no Save button.
- Two chips: the current date (pre-filled with today, dismissible with ✕) and `Время`.
- Four icons: date, tags, project (shows `Без проекта`, opens the same picker as the detail), `⋯`,
  and a send arrow on the right.
- `⛶` at the top right expands to the full editor for everything else.
- Only the composer and the keyboard occupy the bottom; the list remains visible above.

---

## 10. States not exercised (and therefore not claimed)

- Deadline picker (`Крайний срок`), repeat rule (`Перейти к шаблону`), reminder picker, subtask
  sheet, attachments, Pomodoro: **not opened**. Their rows are visible, their behaviour is not
  described anywhere in this folder.
- Completion of a task from the detail (checkbox) — not exercised.
- The archive / trash flows in SingularityApp — not exercised.
- Light theme — the app has none.
- Landscape / tablet.
