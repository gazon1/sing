# UX matrix — same scenario, both apps

Each cell is an observed fact from this session, not a judgement. The verdict column says what the
difference costs the user, because "they do it better" is not an actionable statement.

Legend: ✅ works well · ⚠️ works with friction · ❌ missing or broken · n/e not exercised

---

## Screens and structure

| Category | Singularity Todo | SingularityApp 10.23.0 | Verdict |
|---|---|---|---|
| Where detail lives | full screen, bottom nav still visible | bottom sheet over the list, expands to full screen on scroll | **Ours forces a navigation; theirs keeps context.** Ours also leaves a nav bar on a detail screen, which costs 80dp and invites an accidental tab switch |
| Header | app bar says `Tasks`; back, AI, overflow, search | `⋮` only when collapsed; `‹ Назад` + `⋮` when expanded | **Ours gives no identification.** The task title is only in the body |
| Section count on a title-only task | 13 rows + 6 empty sections ≈ 2 screens | 8 rows, all of them real, ≈ 1.2 screens | **Ours spends ~55% of the scroll on states that convey nothing** |
| Empty optional parameter | always rendered with a hint sentence | not rendered; added via `Больше параметров` | **Structural gap.** Ours cannot be "clean"; theirs can |
| Created/updated metadata | absent | footer `Создана … \| Изменена …` | Ours: an entity field exists (`created_at`, `updated_at`) and is never shown |
| Delete / archive | overflow, no confirm, snackbar + Undo | not exercised | Ours is good here (undo exists) |

---

## Task fields

| Category | Singularity Todo | SingularityApp | Verdict |
|---|---|---|---|
| Title | inline 22sp SemiBold, single line, **clipped without ellipsis**, no top-bar echo | inline, wraps to 2–3 lines | **Ours truncates the most important field.** `18-completed-task.webp` |
| Description | inline plain text, no toolbar | card → full-sheet rich text (images, tables, lists, links) | Ours is faster to start, theirs can hold a document. Copy the sheet; keep inline for short text |
| Checklist — add | `Add item...` field above the items; **focused field ends up behind the IME** | `+` appends a focused row, IME never covers it | **Ours is broken while typing.** `13` vs `singularity-app/10` |
| Checklist — progress | header `Checklist (4/26)`, no bar, no collapse | header `Чек-лист (0/1 выполнено)` + collapse chevron | Draw |
| Checklist — reorder | ❌ none | drag handle per row | ❌ vs ✅ |
| Checklist — delete | red ✕ always visible, no undo | swipe, no undo | both irreversible; ours is louder |
| Subtasks | same card pattern, opens a child screen, 📋 emoji header | `Подзадачи` card with `+` (sheet not exercised) | Ours reuses one row for two kinds, but the emoji header is a regression |
| Project | row, applied on tap, picker flat A→Z, 52dp, no search/hierarchy/emoji | card, applied on tap, picker with 40dp search + hierarchy + emoji, 48dp | **The picker gap is already documented and measured** (`docs/research/2862-…`); unchanged |
| Priority | 5 levels, coloured flags, applied on tap, closes immediately | 3 levels, 2 glyph colours, confirmed with `✓` | Ours is richer; theirs is more deliberate. Keep 5, keep apply-on-tap, but see UXF-08 |
| Due date | separate sheet, opens **below its own actions**, `Сегодня`/`Завтра` | one sheet: 4 icon presets (incl. `Когда-нибудь`), calendar, reset row, time, reminder, Cancel/Save | **Ours is a two-step flow with a hidden commit**; theirs is one decision |
| Time | separate sheet | nested sheet inside the date flow | Ours: two unrelated sheets for one decision |
| Reminder | ❌ **no UI at all** (intents + read model exist) | `Напомнить` row inside the scheduling card | **P0 gap** |
| Recurrence | card prints a Kotlin `toString()` | `Перейти к шаблону` row | **P0 defect** (UXF-01) |
| Deadline | ❌ no row; 150 imported tasks have `end_date` | `Крайний срок` row (not exercised) | Ours silently drops a field it stores |
| Someday | ❌ no row; 310 imported tasks have `someday = 1` | preset `Когда-нибудь` | Ours stores and never surfaces |
| Tags | row + inline create | tag icon in the composer, `Теги` in more-parameters | comparable |
| Estimate | own row + sheet | not present in the detail | Ours is ahead |
| Attachments | empty card `+ Add file` | not visible in the detail | Ours is ahead (once it is not an empty card) |
| Workflow state | `No state / Next up / In progress`, unexplained jargon | no such concept | **Ours invents a concept the reference does not have**; if we keep it, it needs words, not labels |
| Pin | a full row `Not pinned` | pin affordances inside `Больше параметров` | ours costs a row for a rarely used flag |
| Time tracking | `Time` card with Start | Pomodoro behind `Больше параметров` | comparable |
| Logbook | card + hint when empty | not present | Ours is ahead in concept |

---

## Saving and validation

| Category | Singularity Todo — Detail | Singularity Todo — Create | SingularityApp |
|---|---|---|---|
| Commit model | debounced autosave | explicit `Сохранить` | autosave **plus** a `✓ Сохранить` affordance |
| Feedback on save | `Saved` word in the bar while typing | button disabling/enabling | bar switches to `✓ Сохранить` when dirty |
| Back with unsaved input | leaves (flushes first) | `Discard changes?` dialog | leaves, keeps the data |
| Validation | rejected empty title silently reverts | `Title is required` **on a pristine form** | — (send is plain) |
| Failure surface | snackbar / error state | same | not exercised |

**Verdict:** we ship two contradictory contracts for the same entity in one app, and neither of our
two matches theirs exactly. The fix is not "copy theirs"; it is "pick one and apply it to both".

---

## Entry and creation

| Category | Singularity Todo | SingularityApp | Verdict |
|---|---|---|---|
| Create surface | full-screen form | inline composer above the IME + `⛶` to expand | **Ours makes a one-line task feel like a form**; 3 fields are visible before the fold |
| Fields at creation | title, description, checklist, attachments, priority, date, start date, project | title, date, time, project, tags, (all else behind expand) | theirs covers organisation, ours covers content |
| Missing at creation (ours) | tags, recurrence, reminder, estimate, workflow state | — (all reachable via expand/parameters) | **A task cannot be created "complete"; it must be re-opened** |
| Project at creation | row appears **only after the title is non-empty** | always visible | Ours is a layout jump (`19` → `20`) |
| Post-save feedback | returns to the list silently | — | Ours: no confirmation the task was created (`31`) |

---

## Navigation and keyboard

| Category | Singularity Todo | SingularityApp | Verdict |
|---|---|---|---|
| Back destination | whichever list opened the task | the list, with the sheet dismissed | comparable |
| Bottom navigation on detail | **present** | absent (sheet overlays the list) | Ours clips content and invites mis-navigation |
| IME vs focused field | checklist input hidden behind IME; app bar collides with the status bar | never observed to collide | **P0** |
| IME vs Save button | pushed off-screen on Create | n/a (no Save button) | P1 |
| Overflow menu | needs two taps sometimes | `⋮` single tap | P0/P1 depending on cause |
| Search inside a task | ❌ | ❌ | both lack it; not a gap to close here |

---

## Visual system (summary — numbers in `visual-analysis.md`)

| Category | Singularity Todo | SingularityApp |
|---|---|---|
| Row / card height | 52dp (attribute card), 76dp pitch between rows, 84dp empty section | 48–50dp |
| Gaps | 8dp between sections, 28dp between bare rows | 8dp |
| Fill language | 3 fills: `primaryContainer` blue for filled attributes, `surfaceVariant` grey for section cards, `surface` for bare rows | 1 fill: every row the same `#0A1329` |
| Accent | blue on backgrounds *and* icons *and* links | accent on icons/buttons only |
| Text contrast | body-on-blue **3.67:1**, placeholder **2.06:1** | title 18.45:1, muted 3.78:1 |
| Icon language | Material icons + one 📋 emoji + coloured project dots + coloured priority flags/stars | one icon set, emoji only as project identity |
| Language | English rows, Russian dates/placeholders/menus **in the same screen** | Russian only |
| Theme | light only | dark only |
| Empty-state copy | a sentence per empty section | none (the section is not there) |
