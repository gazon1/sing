# Wireframes — target Task Detail (360×800dp reference)

ASCII wireframes, annotated. `│` = screen edge, `▸` = a tap target, `⋯` = overflow.
All measurements are **dp**; anything I could not derive from the current build is marked
`[verify]` rather than guessed. The gutter is 16dp on both sides (`TaskSpacing.screenPadding`).

Legend for annotations: `AMBER` = a measured risk, `VERIFY` = needs a device check before it becomes
an acceptance criterion.

---

## 1. Empty task (title only)

```
┌──────────────────────────────────────────┐  360 dp
│ ←            ⋯                           │  56dp bar: back, overflow
├──────────────────────────────────────────┤
│ ⬜  (untitled)                           │  row 48dp+  AMBER: title max 3 lines
│                                          │
│     Добавить описание…                   │  16sp, onSurfaceMuted (≥4.5:1)
├──────────────────────────────────────────┤  groupGap 16dp
│ 📅  Срок                            ✕    │  48dp row
│ ⬜  Приоритет                            │  48dp row (no mark = default)
│ 📁  Без проекта                          │  48dp row
├──────────────────────────────────────────┤
│ ☑  Чек-лист                          +   │  48dp header row only — no hint, no card
│ ☑  Подзадачи                         +   │  48dp header row only
├──────────────────────────────────────────┤
│ +  Ещё                                   │  48dp — opens the parameter sheet
├──────────────────────────────────────────┤
│ Создана 12 ноя 2026 · изменена только что│  12sp, onSurfaceMuted
└──────────────────────────────────────────┘
```

Total ≈ 56 + 3×48 + 16×3 + 3×48 + footer ≈ **one screen**. Today the same task is ~2 screens
(`singularity-todo/screenshots/03`, `04`).

`AMBER` — the three bare rows (`Срок`, `Приоритет`, `Без проекта`) lose the card fill. That is the
change that fixes the 3.67:1 contrast; check the resulting row separation on the light `surface`
before locking it.

---

## 2. Normal task (date, project, priority, one reminder)

```
┌──────────────────────────────────────────┐
│ ←            ⋯                           │
├──────────────────────────────────────────┤
│ ⬜  Купить хлеб                          │  title 22sp SemiBold, 3 lines max
│     Молоко, хлеб, яйца                   │  description, inline
├──────────────────────────────────────────┤
│ 📅  Чт, 12 нояб           ✕             │  date row, 48dp
│ ⏰  за 15 минут            ✕             │  reminder row — NEW (UXF-02)
│ 🔁  Каждые 3 дня, до 29 янв 2025         │  sentence, not toString (UXF-01)
│ ⚑  Крайний срок           ✕             │  deadline row — NEW (UXF-20)
├──────────────────────────────────────────┤
│ 🚩  Высокий приоритет       ✕            │  one glyph; no mark for default
│ 📁  Квартира                ✕            │  colour dot + name
│ 🏷  продукты дом         ✕              │  tags as chips, one line
├──────────────────────────────────────────┤
│ ☑  Чек-лист                     4/26  ˅ │  header with count + progress bar under it
│     ▓▓▓▓░░░░░░░░░░░░░░░░░░░              │  LinearProgressIndicator, 2dp
│     ☑ Молоко                        ⋮    │  swipe → delete + undo; ⋮ = drag handle
│     ☐ Хлеб                          ⋮    │
│     + Добавить пункт                     │  add row is BELOW the items
└──────────────────────────────────────────┘
```

Notes:

- Every row is 48dp and the row **is** the touch target. The old inflated targets (the 15dp
  overhang measured in `2862-…`) must not come back.
- The planning group (date/reminder/repeat/deadline) is separated from organisation (priority/project/
  tags) by a `groupGap`, and rows inside a group sit on the 8dp rhythm — the same shape the reference
  app uses inside its one scheduling card, without the card.
- `VERIFY` — whether the repeat sentence fits one line at 22 characters; if not, `maxLines = 2` with
  an ellipsis and the full text in the picker.

---

## 3. Fully filled

```
┌──────────────────────────────────────────┐
│ ←            ⋯                           │
├──────────────────────────────────────────┤
│ ⬜  Подготовить запуск                    │
│     Документ-описание…      Открыть ▸    │  long description → document sheet
├──────────────────────────────────────────┤
│ 📅  Пт, 14 нояб, 09:00      ✕           │
│ ⏰  в момент срока          ✕           │
│ 🔁  Каждую пятницу                      │
│ ⚑  Вс, 16 нояб              ✕  Просрочен│  deadline state in words, not only colour
├──────────────────────────────────────────┤
│ 🚩  Срочный                 ✕           │
│ 📁  Работа › Релизы         ✕           │  parent path when the project is nested
│ 🏷  релиз ✕  клиенты ✕  +               │
│ ▸   В работе                            │  workflow state: only because it is set
├──────────────────────────────────────────┤
│ ☑  Чек-лист                   2/5   ˅   │
│ ☑  Подзадачи                  1/2   ˅   │
│ 📎  Файлы                       3   ▸   │  3 attachments → name + size list
│ ⏱  Время                      1 ч 20   ▸ │  tracking with entries
│ 📓  Журнал                      2   ▸   │
├──────────────────────────────────────────┤
│ +  Ещё                                   │
│ Создана 3 окт · изменена 2 мин назад     │
└──────────────────────────────────────────┘
```

The whole screen fits ~1.5 screens even when everything is set, because every group is a header plus
its content and nothing is padded with hints.

---

## 4. Long task (26 checklist items)

```
┌──────────────────────────────────────────┐
│ ←            ⋯                           │
├──────────────────────────────────────────┤
│ ⬜  Backlog sing features                 │
│     (description)                        │
├──────────────────────────────────────────┤
│ 📅  Срок …  🚩 Высокий  📁 Проект        │  scrolls away with the content
├──────────────────────────────────────────┤
│ ☑  Чек-лист                  4/26   ˅   │  collapsed by default when total > 5
│     ▓▓▓░░░░░░░░░░░░░░░░░░░░░░            │
│     (5 rows max, then Показать все 21)   │  ← the cap that makes this screen usable
├──────────────────────────────────────────┤
│ ☑  Подзадачи                 0/3   ˅    │
└──────────────────────────────────────────┘
```

`VERIFY` — the 5-row collapse threshold should be measured against the real profile (a 26-item list
is the 95th percentile here); if 5 rows still push the subtasks off screen, use 3.

Behaviour on expand: the header stays pinned while the items scroll inside their group
(`LazyColumn` with a sticky group header) — `VERIFY` that this is cheap in the current single
`Column` + `verticalScroll`; if not, expand in place and accept the scroll.

---

## 5. Checklist + subtasks interaction detail

```
│ ☑  Чек-лист                    2/5   ˅  │
│     ▓▓▓▓▓▓▓▓░░░                          │  2/5
│     ☑ Молоко                    ⋮       │  ⋮ = drag; swipe-left = удалить
│     ☐ Хлеб                      ⋮       │
│     ☐ Яйца                      ⋮       │
│     + Добавить пункт                     │  ▸ tap → focused row appears HERE, above the IME
│                                          │
│ ☑  Подзадачи                    0/3 ˅   │
│     ☐ Сходить в магазин       ▸          │  ▸ opens the subtask's own screen
│     + Добавить подзадачу                 │
```

Rules:

- The add row is **below** the items (today it is above, `14-checklist-with-item.webp`).
- Tapping it appends a focused row at the end and scrolls it above the IME — the reference app's
  behaviour, and the fix for UXF-03.
- Delete: swipe → row leaves → snackbar `Пункт удалён · Отменить` for 4 s (events exist).
- Drag: long-press the handle → reorder → `sort_order` persisted (`checklist_items.sort_order`
  already exists).

---

## 6. Date editor open

```
┌──────────────────────────────────────────┐
│  ────────                                │  drag handle
│ ✕        Дата и время              ✓     │  one chrome for every picker
├──────────────────────────────────────────┤
│  Сегодня   Завтра   Выходные   Когда-ниб.│  preset chips, 40dp
├──────────────────────────────────────────┤
│  ‹      Ноябрь 2026       ›                │
│  Пн Вт Ср Чт Пт Сб Вс                    │
│   2  3  4  5  6  7  8                    │
│  …                                       │
│  12 ●                                    │  selected = filled primary circle
│  …                                       │
├──────────────────────────────────────────┤
│ 🕘  Время                    09:00   ✕   │  time is a row INSIDE this sheet
│ ⏰  Напомнить                15 мин  ✕   │  reminder too — one scheduling decision
├──────────────────────────────────────────┤
│  Сбросить дату                           │  text button, left
│                  Отмена        Сохранить │  primary on the right
└──────────────────────────────────────────┘
```

- Opens at full content height (`skipPartiallyExpanded = true`) so the action row is visible — today
  it opens below the fold (`07-date-sheet-collapsed.webp`).
- One `✓` in the chrome **or** the button row, not both (`VERIFY` which the sheet host gives us).
- `Когда-нибудь` maps to `tasks.someday`, which today has no surface at all.

---

## 7. Priority editor open

```
┌──────────────────────────────────────────┐
│  ────────                                │
│ ✕          Приоритет                     │
├──────────────────────────────────────────┤
│  🏳  Без приоритета                  ○   │  no colour = default
│  🚩  Низкий                          ○   │
│  🚩  Средний                         ●   │  current
│  🚩  Высокий                         ○   │
│  🚩  Срочный                         ○   │  error tint + filled icon
└──────────────────────────────────────────┘
```

- Keep apply-on-tap (today's behaviour): the sheet closes and the row updates.
- The clear action lives **on the row** (✕), not in the sheet — one way to clear.
- `Срочный` must be visually distinct from `Высокий` at 20dp: filled + `error` for Urgent, `error`
  outline for High (`VERIFY` that the two read apart on the phone).

---

## 8. "Ещё" — the parameter sheet

```
┌──────────────────────────────────────────┐
│  ────────                                │
│ ✕            Ещё                         │
├──────────────────────────────────────────┤
│ +  Напоминание                           │  only parameters that are NOT on screen
│ +  Крайний срок                          │
│ +  Оценка                                │
│ +  Вложение                              │
│ +  Журнал                                │
│ +  Связанная задача                      │
│ +  Состояние                             │
└──────────────────────────────────────────┘
```

- One tap adds the element to the body **with its editor open** (the user came here to set it).
- The list is derived from what is currently hidden — not a static list — so it shrinks as the task
  fills.
- Unlike the reference app there is **no pin**: on our screen the rule "shown when it has a value"
  already decides visibility, and a pin would be a second mechanism saying the same thing.

---

## 9. States the wireframes must also cover (for the Compose tests)

| State | Expected |
|---|---|
| loading | skeleton or spinner, no empty-state copy |
| error | message + `Повторить` |
| saving | no visible change; `Saved` pill pulses in the bar |
| IME open (any field) | focused field and the row above it fully visible; bar not under the status bar |
| completed task | title struck through, date row replaced by `Выполнено 12 ноя 2026`, clear affordances gone |
| task in trash | read-only, `Восстановить` in the bar |
| long title | 3 lines then ellipsis; the bar shows the title once the hero scrolls away |
| 0 projects | picker shows only `Без проекта` + `Создать проект` |
| 0 tags | the tags row reads `Метки +` and opens the picker; it is not hidden |
