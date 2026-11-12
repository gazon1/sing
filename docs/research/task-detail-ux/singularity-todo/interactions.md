# Singularity Todo — interaction catalogue

Every entry was performed on the device. "Taps" counts discrete taps; a swipe is counted separately
because it is a separate gesture, not a tap. "Transitions" counts changes of full screen (a bottom
sheet is not a screen transition).

---

## 1. What each control does when touched

| Control | Gesture | Immediate result | Confirmation | Undo | Verified |
|---|---|---|---|---|---|
| Title text | tap | caret + underline, IME opens, `Saved` pill area activates | silent autosave after 300 ms | none (draft reverts if cleared) | DB: `title` changed ✅ |
| Description | tap | caret + IME | silent autosave | none | ✅ |
| Hero checkbox | tap | completion toggles; row leaves Today | none | none on the detail; the list has its own affordances | a11y label flips to `Задача выполнена` ✅ |
| Workflow state row | tap → sheet → tap | state applies, sheet closes | none | none | `No state` → `Next up` persisted ✅ |
| Priority row | tap → sheet → tap | level applies, sheet closes | none | none | DB `priority High` after tap ✅ |
| Estimate row | tap → sheet → tap | value applies | none | none | — |
| Due date row | tap → sheet (partial) → scroll → pick → OK | date applies | `OK` inside the sheet | ✕ on the row | — |
| Due date ✕ | tap | date clears | none | none | — |
| Project row | tap → picker → tap | project applies, picker closes | none | none | ✅ (project cleared and re-set during the session) |
| Tags row | tap → picker | tags apply | none | none | — |
| Pin row | tap | pin toggles | none | none | — |
| Checklist `+` / Enter | tap / IME action | item created **and persisted** | none | none | DB: 2 588 → 2 589 ✅ |
| Checklist item checkbox | tap | toggles; header counter updates `0/1` → `1/1` ✅ | none | none | ✅ |
| Checklist item ✕ | tap | item deleted immediately | **none** | **none** | back to `No checklist items yet` ✅ |
| Subtask row title | tap | opens the subtask's own detail | — | — | — |
| `+ Add file` | tap | system file picker | — | — | — |
| Overflow → `Архивировать` | tap | task leaves the list | none | undo event exists (`UndoArchive`) | archived one task by accident ✅ |
| Overflow → `Удалить` | tap | task trashed | **none** | snackbar `Task deleted` + `Undo` (`23`) | ✅ |
| Create → `Сохранить` | tap | returns to the list, task appears | none | none | DB: task row created ✅ |
| Create → Back when dirty | tap | `Discard changes?` (`21`) | dialog | `Keep editing` | ✅ |

---

## 2. Scenario cost

| Scenario | Taps | Swipes | Transitions | Feedback | Risk |
|---|---:|---:|---:|---|---|
| Complete a task from the list | 1 | 0 | 0 | row dims/leaves | low |
| Change priority of an open task | 3 | 0 | 0 | label + colour change | low |
| Set a due date | 5 | 1 | 0 | chip text changes | **medium** — actions are below the fold |
| Clear a due date | 2 | 0 | 0 | row returns to `Добавить дату` | low |
| Add one checklist item | 4 | 1–2 | 0 | counter appears | **high** — the field is behind the IME (`13`) |
| Delete a checklist item | 2 | 1–2 | 0 | row disappears, counter drops | **high** — no undo |
| Create a task with only a title | 3 | 0 | 0 | returns to the list silently | low |
| Create a task with a project | 5 | 0 | 0 | project row appears only after typing | medium — the row is not where the detail puts it |
| Create a task with a reminder / recurrence / estimate | **impossible** | — | — | — | the fields do not exist on Create |
| Delete a task | 3 | 0 | 0 | snackbar + Undo | medium — the overflow needs two taps on this device |
| Reorder checklist items | **impossible** | — | — | — | no affordance |

---

## 3. Keyboard behaviour

1. **Checklist input**: tapping `Add item...` opens the IME and the field **stays behind it**
   (`13-checklist-add-keyboard-covers-field.webp`). The content does not scroll to keep the focused
   field visible.
2. **Top bar vs IME**: with the IME open the top app bar is drawn under the status bar — `Tasks`
   overlaps the clock (`13`, `22`). This is a missing window-inset (`imePadding`/`statusBarsPadding`)
   on the detail's Android host, not a per-field problem.
3. **Title editing**: works — the caret and underline appear, the bar gains `Saved`, and the
   `Quick actions` card is inserted between the description and the state row (`22`). The card is
   `tertiaryContainer` (#5B4A63-family purple) and is the only purple on the screen.
4. **Create screen**: the keyboard does not hide the title field, but the Save button is pushed
   off-screen while typing, so the user must dismiss the IME to save.
5. **Back with the IME open** closes the IME only; a second Back leaves the screen. On the *detail*
   the second Back leaves **without any prompt** (correct, because it autosaves — see 4).

---

## 4. Saving: what is promised vs what happens

| Promise (ADR / DoD) | Observed |
|---|---|
| Inline edits are debounced 300 ms and silent | ✅ — no snackbar while typing |
| A `Saved` indicator exists | ⚠️ a `Saved` word appears in the bar while typing and disappears; there is **no "Saved 2 min ago"** resting state |
| Explicit actions emit one confirmation | ⚠️ completion/delete do; **checklist add/toggle/delete emit nothing** |
| Back flushes a pending keystroke (`flushDraft`) | not directly observable; the edited title survived Back ✅ |
| Changes survive fast switching between fields | ✅ — title edit, then priority, then checklist add/toggle/delete all persisted in order |
| Create asks before discarding | ✅ (`21`) |
| Detail asks before discarding | n/a — it saves, so there is nothing to discard |

---

## 5. Interaction defects observed (each reproducible)

1. **The overflow needs two taps.** On several screens the first tap on `⋮` did nothing; the second
   opened the menu. Reproduced at least four times. Candidate cause: the menu is published to the
   Android shell as an actions block (`TaskDetailTopBar.kt:35-41`), so the shell may be consuming the
   first press. Recorded in `comparison/ux-findings.md` as UXF-13; needs a code-level follow-up.
2. **Menu items are not reachable by clicking their text node.** `Архивировать` / `Удалить` are
   `clickable=false` in the tree; a tap on the label text closed the menu without acting once, and
   acted another time. This is the hit-testing smell that
   `docs/research/2862-project-navigation-comparison.md` already found in the menu tree.
3. **Date sheet opens below its own actions.** `07` → `08` shows the required scroll.
4. **No undo anywhere except task delete.** Checklist item deletion, project clearing, priority
   change, date clearing are all irreversible.
5. **Sheet chrome is inconsistent**: `Priority`, `Workflow state`, `Estimate`, `Project`, `Tags`
   share a host (drag handle + ✕ + centred title); the date and time sheets use a different host
   with `Clear / Cancel / OK`; the reminder sheet does not exist. Three patterns for one job.
6. **A destructive ✕ is the loudest element in the checklist.** On `16` the red ✕ column dominates
   26 rows of content.

---

## 6. Navigation observations

- The detail is entered from Today, Upcoming, a project, search, the archive and a deep link; **Back
  returns to whichever of those was used** (verified with search: Back from the detail returns to the
  search results, not to Today).
- The bottom navigation bar stays visible on the detail and covers the last ~80dp of content
  (`04`: the Logbook card is cut by the bar).
- The app bar says `Tasks`, never the task's title — with three or four details stacked in a back
  stack the user has no header to orient by.
- The Create screen is reached from the FAB and from a section's `+`; it is not reachable from a
  task that already exists (no "open in editor" path — editing *is* the detail).
