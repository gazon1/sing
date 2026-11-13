---
title: Task Detail attribute rows read on semantic surfaces
date: 2026-11-13
status: accepted
tags: [ux, task-detail, accessibility, theme, compose]
---

# Task Detail attribute rows read on semantic surfaces

## Context

The Task Detail's attribute rows were painted with a set of choices that no gate could
check, because none of them was written down:

- The priority row's *set* state was an accent fill (`primaryContainer`) around the
  value — the same fill the accent picker uses, so a task whose priority was the
  default looked like a task the user had marked.
- The muted text tier was `onSurfaceVariant` at 72% opacity, and the placeholder was
  `onSurfaceVariant` at 55%. Measured against the surfaces they were drawn on, the
  muted tier cleared 4.5:1 on `surface` (4.90–4.97) but **failed** it on
  `surfaceVariant` (4.38–4.46); the placeholder failed everywhere (2.06–2.8).
- Row heights were literals scattered across the editor rows, and the title, the
  description and the attribute labels were written as `16.sp` / `22.sp` rather than
  read from a typography slot, so the user's `fontSizeScale` did not reach them.
- The subtasks section header was a literal emoji — `Text("\uD83D\uDCCB", …)` — a
  glyph the app cannot tint and cannot scale with the theme.
- Priority was signalled by colour: raw hues from the priority palette
  (amber for Medium, red for High and Urgent), most of which measure below 3:1 on a
  light surface, and the accent fill made the *default* level look exceptional.

Issue [#2878](https://github.com/gazon1/sing/issues/2878) asks for accessible visual
foundations for exactly these rows, without turning the issue into a redesign of the
task list or the project presentation.

Two reversals hide in that list. The accent fill and the emoji were both accepted at
some point and never written down as a decision — the ADR-compliance audit (#2872)
records them as reversals of decisions that were never recorded. Reversing them
without recording the replacement would repeat the mistake, so this ADR records it.

## Idea

Make the Task Detail's visual language a function of the theme, and let the theme be
the only place where the palette is decided:

- every surface a row stands on is a named Material role, and every text role on it
  clears WCAG AA;
- the level of a priority is carried by a **shape**, and the colour carries only one
  thing: whether the level is an exception;
- a row's geometry comes from the shared tokens, and its type comes from the
  typography slots, so the user's text scale reaches it.

## Decision

### 1. Semantic surfaces, readable foreground roles

`TaskAttributeCard`'s `containerColor` is now always `MaterialTheme.colorScheme.surface`.
The accent fill (`primaryContainer`) is gone. "This attribute is set" is said by the
**icon tint** (`primary`) plus a medium-weight label in `onSurface`; an unset attribute
reads in `onSurfaceVariant`. No new surface role was invented — `surface`,
`surfaceVariant` and `background` are the three surfaces the detail actually paints
on, and the row reads the one it stands on.

### 2. The muted tier is a token that clears AA

`core.ui.theme.mutedTextColor` moves from 72% to **85%** opacity over `onSurfaceVariant`.
Measured over all eight accents in both modes, that clears 4.5:1 on `surface`
(7.24–10.0), on `background` and on `surfaceVariant` (5.43–6.27). The task surfaces'
placeholder now reads the same token: a placeholder is text, and text has to clear AA
where it stands.

The value is exposed as `MUTED_TEXT_ALPHA` and a pure `mutedTextColorOf(scheme)`, so a
test measures the token the app actually paints rather than a copy of the arithmetic.

### 3. One icon language for priority, and the default is not an alarm

The priority row and the priority sheet share one glyph per level
(`TaskPriorityIcons.kt`):

| Level | Glyph | Tint |
|---|---|---|
| None | outlined flag | muted |
| Low | down arrow (`LowPriority`) | `onSurface` |
| Medium | filled flag | `onSurface` |
| High | warning triangle | `error` |
| Urgent | doubled chevron (`PriorityHigh`) | `error` |

Two consequences are deliberate:

- **Medium is not accented.** Medium is the level a task has by default; a filled
  accent behind it made the default look like an alarm. The shape says "a priority is
  set", the colour says whether it is an exception, and Medium is not one.
- **The raw priority hues are not used in the detail.** On a light surface they
  measure below 3:1, so they cannot carry the level. They stay in the task list,
  where the marker wears a rim that keeps it perceivable.

This deviates from the plan that proposed the same flag glyph for every level: five
shapes cost five rows of a legend and read the level faster, and no level is
signalled by colour alone.

### 4. Rows are 48dp and the spacing is token-driven

The editor rows (priority, due date, estimate) carry
`Modifier.heightIn(min = TaskSpacing.rowMinHeight)`, and `rowMinHeight` is
`TouchTarget.MinSize` — 48dp. The clear-button spacer reuses the same size as
`TaskSpacing.clearButtonSlot`. The spacing literals in `ExtraSectionCard` and the
attachments add-row became `TaskSpacing` values (the same numbers).

The 8dp-inside-a-group / 16dp-between-groups restructure was **not** done here: it
requires re-indenting a ~200-line block and changes the screen's rhythm, which is a
redesign rather than a foundation.

### 5. Typography comes from the slots

The title reads `titleLarge.copy(fontWeight = SemiBold)`, the description reads
`bodyLarge`, and the attribute card label and the three editor rows read `bodyLarge`.
The hard-coded `16.sp` / `22.sp` are gone, so `fontSizeScale` reaches all of them.

### 6. The emoji is gone, and the gate that keeps it gone

The subtasks header draws `Icons.Filled.Checklist`; the checklist section and the
checklist row draw the same glyph. One icon language for the checklist affordance.

`NoEmojiInTaskIconsTest` reads every production file under `feature/tasks` with its
comments stripped and fails on any emoji code point — **literal or escaped**, with
surrogate pairs decoded — in the pictographic plane, the dingbats and the
variation-selector. The escaped form is the one that came back: it survives a search
for emoji characters and hides a pictograph behind four hex digits.

## Rationale

Contrast is not a taste question: a muted tier that measures 4.38:1 on a surface the
user can also see is unreadable, and the placeholder at 2.06:1 is decorative. Both
numbers were measured over the real palette (all eight accents, light and dark) and
are pinned by `TaskDetailContrastTest`, so a future palette change that breaks them
fails the build instead of shipping.

The icon language answers the issue's "priority is not signalled by colour alone".
Shape carries the level, colour carries the exception. That is also why Medium lost
its accent: the accent fill was doing the job of the shape, and doing it wrongly.

The 48dp row and the token-driven spacing are a floor, not a redesign: they say the
rows share one rhythm, and they say it through the tokens that already exist rather
than through new literals.

## Consequences

- `TaskSemanticColors.priorityMeta` and the raw priority hues are still used by the
  task **list** (`PriorityIndicator`, `PriorityChip`). The detail and the list
  therefore signall priority differently — the list by a rimmed hue, the detail by
  shape. Unifying them is a separate change and would touch the list, which this
  issue explicitly does not.
- The group-spacing restructure (8dp inside a group, 16dp between groups) is still
  open.
- The before/after evidence is a set of ad-hoc captures, not a screenshot suite:
  the repository has no screenshot framework (ADR
  `2026-09-30-roborazzi-not-built-superseded.md`), and building one is issue
  #2887. The captures live in `docs/research/2878-task-detail-attribute-rows/`,
  and no gate asserts them — the colour numbers are pinned by
  `TaskDetailContrastTest` and the type-scaling numbers by
  `TaskDetailFontScaleTest`.
- `mutedTextColor` changed value for every screen that reads it, not only the task
  detail. It is lighter than the 72% tier was and darker than the plain
  `onSurfaceVariant` — a deliberate move towards legibility.

## Links

- Issue [#2878](https://github.com/gazon1/sing/issues/2878), parent [#2872](https://github.com/gazon1/sing/issues/2872)
- ADR `2026-09-30-roborazzi-not-built-superseded.md` — no screenshot framework yet
- ADR `2026-09-07-task-detail-document-style.md` — the document-style Task Detail
- skill:singularity-todo-task-detail-ux, skill:singularity-todo-shared-ui-components
