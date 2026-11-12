# Visual analysis — measured, not judged from taste

Device: 720×1600 px, density 320 → **1dp = 2px**. Light theme, font scale 1.0.
Values below are pixels as read from the screenshots in this folder; dp is px ÷ 2.
Contrast ratios are WCAG 2.x relative luminance computed from the sampled sRGB values.

---

## 1. The palette is generated, so the defect is in the pairing, not the colour

`SingularityTheme.kt` builds the scheme with `materialkolor`'s `dynamicColorScheme` from one of eight
accent seeds at `ACCENT_CONTRAST_LEVEL = 0.5`. With the current Blue seed (`#2196F3`) the scheme
produces:

| Role | Value | Where it is used |
|---|---|---|
| `surface` | `#F8F9FF` | page background |
| `surfaceVariant` | `#DFE2EB` | `ExtraSectionCard` fill |
| `primary` | `#0E5A8A`-family | icons, links, cursors |
| `primaryContainer` | `#46709E` | `TaskAttributeCard` when `isActive` |

Three consequences worth carrying into implementation:

1. **The filled attribute rows change colour with the user's accent.** A Purple-accent user gets
   purple blocks. Any rule of the form "filled means accent" therefore turns the screen into an
   accent-coloured wall under every theme.
2. **The date picker's selected day (`#00305F`) does not come from this scheme** — Material's
   `DatePicker` uses its own secondary-derived container, so the circle reads as a different blue
   from the app's primary. Two blues, one screen (`08-date-sheet-expanded.webp`).
3. **A contrast test must iterate the matrix**: 8 accents × light/dark. A token change that fixes
   Blue can break Yellow (`#FFEB3B` as a seed produces a very different `primaryContainer`).

---

## 2. Measured pairs and ratios

### Singularity Todo (light)

| Pair | Colours | Ratio | AA (4.5) |
|---|---|---:|---|
| Title on surface | `#0E1116` on `#F8F9FF` | **16.22** | ✅ |
| Row label on `primaryContainer` (**the filled rows**) | `#0E1116` on `#46709E` | **3.67** | ❌ |
| Description placeholder on surface | `#AEB0B6` on `#F8F9FF` | **2.06** | ❌ |
| Section card vs page | `#DFE2EB` vs `#F8F9FF` | **1.23** | — (non-text) |
| Hint text on section card | `onSurfaceVariant` on `#DFE2EB` | ≈7.2 | ✅ |
| `Undo` on snackbar | primary on inverse surface | ≈3.0 | ⚠️ borderline at 14sp |

The two ❌ are the important ones: **3.67:1 is body text the user reads constantly** (every task with
a date and a project), and 2.06:1 is the copy that teaches the empty states.

### SingularityApp (dark) — for reference, not as a target

| Pair | Colours | Ratio |
|---|---|---:|
| Title on sheet | `#FFFFFF` on `#0A1329` | **18.45** |
| Muted subtitle (project) | `#6B7180` on `#0A1329` | **3.78** ⚠️ |
| Card vs page | `#0A1329` vs `#080D21` | **1.13** |

Note the last row: their card separation is *lower* than ours (1.13 vs 1.23) and still reads clearly,
because the separation is carried by an 8dp gap, not by the fill. **A fill delta is not a way to
express grouping.** Their muted subtitle also fails AA — so their typography is not a model for
contrast, only for hierarchy.

---

## 3. Typography

### Singularity Todo — what is actually used in the detail

| Element | Style | Size |
|---|---|---|
| Title | literal | **22sp**, SemiBold (`TaskTitleRow.kt:141`) |
| Description | literal | **16sp / 22sp line height** (`TaskDescriptionField.kt:41`) |
| Attribute row labels (priority, date, estimate, project, tags, pin, state) | literal | **16sp** (`TaskAttributeCard.kt:103` and the three sibling rows) |
| Section card label (`Checklist`, `Subtasks`, …) | `labelMedium` | 12 / 16 |
| Section hint (`No checklist items yet…`) | `bodySmall` | 12 / 16 |
| Checklist item text | `bodyLarge` | 16 / 24 |
| Sheet title | `titleMedium` | 16 / 24 |
| `Saved` | `labelMedium` | 12 / 16 |

Two structural problems:

1. **The prominent sizes are raw `sp` literals, not theme slots.** Only the secondary text uses
   `MaterialTheme.typography.*`. So the app's own `fontSizeScale` (built in `createTypography`) does
   **not** scale the title, the labels or the description — the elements that matter most for
   accessibility. Verified in `Type.kt:55-106`: every slot is multiplied by the user's scale, but a
   literal `22.sp` is not a slot.
2. **The hierarchy is flat.** 22 / 16 / 16 / 12: the title is only 1.375× the row labels, so the
   hero does not dominate. Measured ink: title 44px tall, row label 30px — a ratio of 1.47 in ink.

### SingularityApp (ink-measured, ±1–2sp)

| Element | Ink | Inferred |
|---|---|---|
| Hero title | 85px for 2 lines → ~42px per line | ≈24sp, bold |
| Row label | 21px x-height band | ≈15–16sp |
| Muted subtitle / meta | ≈18px band | ≈13–14sp |
| Month header in the date sheet | ≈44px | ≈22sp |

Their title is ~24sp against ~16sp rows: **1.5×**, and the muted third level sits well below. The
ratio is close to ours; what differs is that their rows are not competing with the title through
colour.

---

## 4. Spacing and density (measured)

| | Singularity Todo | SingularityApp |
|---|---:|---:|
| Screen side padding | 16dp (32px) | 16dp (32px) |
| Hero top gap (app bar → title) | **84dp** (168px, `02`) | ~0 (title directly under the bar) |
| Attribute row height | 52dp (104px) | 48–50dp |
| **Distance between attribute rows** | **76dp** pitch | **56–58dp** pitch (48 + 8 gap) |
| Section card gap | 8–9dp | 8dp |
| Empty-section card height | **84dp** (`03`, Related tasks) | — (not drawn) |
| Checklist item vertical padding | 4dp + 18dp control | 48dp row |
| Bottom nav height | 80dp | 80dp |
| Card corner radius | 14dp | ≈12dp |

The single largest density difference is the **76dp pitch** created by bare rows that carry no card:
`TaskEditorPriorityRow`, `TaskEditorDueDateRow`, `TaskEditorEstimateRow` each reserve a 48dp target
plus 28dp of dead space, while `TaskAttributeCard` rows are 52dp cards. Two different rhythms
alternate down the same column, which is why the scroll reads as inconsistent rather than merely
long.

---

## 5. Grouping — the three fills

The screen uses three separate ways to say "these things belong together":

| Fill | Used by | Meaning conveyed |
|---|---|---|
| `primaryContainer` (#46709E) | state row, project row | "this attribute is set" |
| `surfaceVariant` (#DFE2EB) | Related tasks, Repeats, Checklist, Subtasks | "this is a section" |
| `surface` (transparent) | priority, estimate, date, tags, pin, time | "this is a row" |

So identical semantics (an attribute with a value) are painted two different ways depending on
whether the attribute happens to be a card or a bare row, and a section is grey while a set attribute
is blue. A user cannot learn the rule because there isn't one.

SingularityApp uses **one** fill for everything and carries structure with gaps, dividers inside a
group, and position. Their one grouping device — a card with hairline dividers containing
date / reminder / repeat / deadline — is the only place a border exists, and it marks a real
cluster: *when* the task happens.

---

## 6. Icons

| | Singularity Todo | SingularityApp |
|---|---|---|
| Set | Material icons | Material-ish Flutter icons |
| Priority | ★ in lists, flag in the detail, 5 colours; `High` and `Urgent` both red | ★ in lists, ⓘ in the sheet, 3 levels |
| Project identity | coloured dot (14dp) + name | emoji in the menu and picker; a coloured ring when no emoji |
| Section icons | checklist `☑` (Material), attachments 📎 (Material), time ⏱ (Material), logbook (Material), **subtasks 📋 (emoji)** | not applicable (no section headers) |
| Action icons | `×` clear, `⋮` overflow, ✨ AI, 🔍 search | `✕`, `⋮`, `✓`, `⤢` |
| Emoji in production UI | **one** (`SubtasksSection.kt:66`) | many, but always as project identity |

The single emoji is worse than many: it sits in a column with four Material icons, at a different
optical weight, and it is the only glyph in the screen that the theme cannot tint.

---

## 7. Language

Measured across the screens: **English labels with Russian dates, placeholders and menus, inside the
same card.**

| Language | Strings |
|---|---|
| English | `No state`, `Next up`, `In progress`, `No priority`, `Low/Medium/High priority`, `Urgent`, `Add estimate`, `Add tags`, `Not pinned`, `Related tasks`, `Repeats`, `Checklist`, `Subtasks`, `Attachments`, `Time`, `Logbook`, `Add item...`, `Add subtask...`, `+ Add file`, `No checklist items yet…`, `Quick actions`, `Ask AI`, `State`, `Estimate`, `Project`, `Tags`, `Clear`, `Cancel`, `OK`, `Saved`, `Discard changes?`, `Title is required`, `Task deleted`, `Undo` |
| Russian | `Введите описание задачи...`, `Добавить оценку`, `Добавить дату`, `Добавить оценку`, `Чт, 12 ноя 2026`, `Сегодня`, `Завтра`, `Выберите дату`, `Архивировать`, `Удалить`, `Сохранить`, `Закрыть лист`, `Маркер перемещения`, `Сбросить приоритет`, `Сбросить дату`, project names |

Even the same concept is split: the date sheet's own buttons are `Clear / Cancel / OK`, while the row
that opened it says `Сбросить дату`. The search screen shows priority as `Средний` while the detail
shows `Medium priority` (`25-search-results.webp` vs `02`).

This is a **localisation defect with a measurable cause**: strings are literals in composables, while
the parts that were localised use `Res.string.*`. `Res.string.priority` exists and is used for the
sheet title (`TaskEditorSheetsHost.kt:103`) next to hardcoded English option labels
(`TaskEditorPrioritySheet.kt`).

---

## 8. Density and overload — where the eye goes

On `02-task-detail-minimal-top.webp`, ranked by salience:

1. the two `#46709E` blocks (highest contrast mass on the screen);
2. the 22sp title;
3. the red ✕ on the priority row;
4. the oversized empty areas between rows.

The title should be first. On `singularity-app/02`, the eye goes title → project subtitle → the first
card, which is the intended order.

On `16-rich-checklist-26-items.webp` the red ✕ column repeats 26 times and becomes a vertical stripe —
the loudest thing in a screen whose content is text.

---

## 9. Concrete proposals from the measurements

| Problem | Change | Where |
|---|---|---|
| Filled rows at 3.67:1 and accent-dependent | drop the fill; mark "set" with icon tint + medium weight | `TaskAttributeCard.kt:43-44` |
| Placeholder at 2.06:1 | one muted token ≥ 4.5:1 | new token + `TaskDescriptionField`, hints |
| 76dp pitch vs 52dp cards | one 48dp row rhythm, 8dp gaps | `TaskEditorPriorityRow/DueDateRow/EstimateRow` |
| 84dp top gap | 16dp, hero directly under the bar | `TaskEditorContent.kt:193-201` |
| 84dp empty cards | remove the card, keep a one-line add row | `ExtraSectionCard.kt` |
| Emoji section icon | Material icon, one set | `SubtasksSection.kt:66` |
| Two glyphs for priority | one glyph, no mark for the default level | `TaskMarkingsRow`, `PriorityIndicator`, `TaskEditorPrioritySheet` |
| Raw `sp` literals ignore `fontSizeScale` | map to `titleLarge`/`bodyLarge` slots | `TaskTitleRow.kt:141`, `TaskDescriptionField.kt:41`, `TaskAttributeCard.kt:103` |
| Mixed language | literals → `Res.string.*` + a lint gate | `feature/tasks` |

Each of these is a small change; none of them requires touching the state model. That is why the
visual work can ship **before** the structural work (see `implementation-plan.md`).
