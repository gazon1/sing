# SingularityApp — interaction catalogue

Everything here was performed on `0O65321F32204DD5` against the same account data as Singularity
Todo. Flutter exposes no accessibility tree, so the record is visual + database-free (the app's
storage was not pulled). Where a behaviour was not exercised it says so.

---

## 1. Control → result

| Control | Gesture | Result | Confirmation | Undo |
|---|---|---|---|---|
| Title in the hero | tap | caret + IME | autosave (verified: an item added and abandoned survived Back) | none |
| Description card | tap | full-sheet rich-text editor with `✕ / Описание / ✓` and a formatting toolbar | `✓` | `✕` discards |
| Checklist `+` | tap | inline focused row appears in the card; IME opens; bar turns into `✓ Сохранить` | autosave on commit | none; swipe-to-delete has no undo |
| Checklist item drag handle | drag | reorder (affordance present; drag not completed) | — | none |
| Checklist item swipe left | swipe | deletes immediately | none | **none** |
| Project card | tap → picker → tap | applies at once, sheet closes | none | **none** (verified — my tap changed the project and there was no snackbar) |
| Priority card | tap → sheet → tap level → `✓` | applies on confirm | `✓` | none |
| Date card | tap → sheet → presets/calendar → `Сохранить` | applies on `Сохранить` | `Сохранить` / `Отменить` | `Сбросить дату` row, and `✕` on the row itself |
| `Время` inside the date sheet | tap | second sheet over the first | `Сохранить` | `Отменить` |
| `Больше параметров` | tap | parameter picker with a pin per row | — | — |
| Back on a dirty task | system back | leaves the screen; **no discard prompt** | — | — |
| Quick-add send | tap | task created | — | — |

---

## 2. Scenario cost

| Scenario | Taps | Swipes | Transitions | Notes |
|---|---:|---:|---:|---|
| Open a task from the list | 1 | 0 | 0 | it opens as a sheet over the list |
| Change the project | 3 | 0 | 0 | pick → row; applies instantly |
| Change the priority | 4 | 0 | 0 | pick → level → `✓` |
| Set a date with a preset | 3 | 0 | 0 | pick → `Завтра` → `Сохранить` |
| Set a date on the calendar | 5 | 0 | 0 | pick → day → `Сохранить` |
| Reset the date | 3 | 0 | 0 | pick → `Сбросить дату` → `Сохранить` |
| Add a checklist item | 2 | 0 | 0 | `+` then type; commit on IME action |
| Delete a checklist item | 1 | 1 | 0 | swipe; irreversible |
| Create a task with only a title | 2 | 0 | 0 | FAB → type → send |
| Create a task with a project | 4 | 0 | 0 | composer → project icon → row → send |
| Create a task with a description | 4 | 0 | 0 | composer → `⛶` → description card → `✓` → send |

The create numbers are the interesting ones: the composer carries date, time, tags and project, so
the "full editor" is needed only for description, checklist, reminder, deadline, place and Pomodoro.

---

## 3. Saving semantics (verified)

The app shows `✓ Сохранить` in the bar when the task is dirty, which suggests an explicit-commit
model. It is not: **changes are persisted without pressing it.**

Evidence: a checklist item `sg-probe-item` was typed and the screen was left with Back (twice, no
prompt). Re-opening the task showed the item present. The `✓ Сохранить` button is therefore a
"finish editing" affordance, not the only commit path.

Consequences to carry into our design:

- Their back-swipe cannot lose data — which is why they can afford no discard prompt.
- Our create screen uses the opposite model (explicit `Сохранить` + discard prompt), and our detail
  uses *their* model. **We have both models in one feature**; they have one.

---

## 4. Keyboard and insets

- The quick-add composer and the checklist inline row both sit **above** the keyboard; the content
  they belong to stays visible. No field is ever hidden behind the IME in the states exercised.
- The description editor's toolbar is docked directly above the IME, which keeps formatting reachable
  while typing.
- Back with the IME open dismisses the IME only; a second Back leaves the task (and does not prompt).

---

## 5. What is noticeably worse than ours

Recording these matters as much as the strengths — a target design should not inherit them.

1. **No undo for destructive actions.** Checklist delete (swipe), project change, date reset: all
   immediate and irreversible. We have undo for delete and archive.
2. **Names are cut without ellipsis** in the picker (`лазернуая коагуляция сетчатк`).
3. **Back disappears while dirty** — it is replaced by `✓ Сохранить`, so "leave without finishing"
   has no visible affordance.
4. **Only 3 priority levels**, with `High` and `Low` distinguished only by a tiny icon and a word.
5. **No search inside the task.** With 26 checklist items the only way to find one is to scroll.
6. **The sheet→screen transition changes the chrome**, so the same task has two different back
   affordances depending on how far it is scrolled.

---

## 6. Not exercised (stated, not guessed)

- Reminder picker, deadline picker, repeat-rule editor, subtasks sheet, attachments, Pomodoro.
- Completing a task; deleting a whole task; the archive.
- What the pin in `Больше параметров` does.
- Multi-select or bulk actions inside the task.
- Behaviour with 0 projects, with an empty account, or offline.
