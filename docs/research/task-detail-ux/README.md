# Task Detail UX — Singularity Todo vs SingularityApp

Status: research recorded 2026-11-12 on branch `gazon1/bichir`. **No production code was changed.**
Screenshots, measurements and every claim below were taken on one physical phone, on one profile,
with the same imported data in both apps.

Scope: the **Task Detail** — viewing, editing and creating a task — on **Android phone**.
Desktop and tablet are only used to judge how portable a decision is; they are not audited here.

> Screenshots are stored as **WebP** (`*.png` is gitignored repo-wide; the existing research
> assets use WebP for the same reason). Two file names exist in both app folders
> (`01-today-list`, `06-priority-sheet`); outside an app folder a bare file name means
> **Singularity Todo**, and a `singularity-app/…` prefix means the reference app.

---

## What this folder is for

Another developer should be able to start implementing without repeating the research:

| Question | Answered in |
|---|---|
| What is wrong with our Task Detail today? | `comparison/ux-findings.md` (`UXF-nn`), `singularity-todo/` |
| How does the same scenario work in SingularityApp? | `singularity-app/` |
| Why is their version better (or not)? | `comparison/ux-matrix.md`, `comparison/visual-analysis.md` |
| What should our screen look like and do? | `implementation/target-ux.md`, `implementation/wireframes/` |
| Which files have to change? | `implementation/current-architecture.md` |
| Which accepted decisions did we already take, and did we follow them? | `implementation/adr-compliance.md` |
| In what order do we work? | `implementation/implementation-plan.md` |
| Which tests guard it? | `testing/coverage-gaps.md`, `testing/acceptance-criteria.md` |
| How was the evidence collected? | `environment.md`, `test-data.md` |

---

## The ten findings that matter

Ordered by user impact, not by category. Each is a measured observation, not an opinion;
"opinion" appears only where it is labelled as such.

1. **The recurrence row prints a Kotlin `toString()`.** A repeating task shows
   `Interval(base=FROM_DUE, amount=3, unit=DAY, termination=RecurrenceTermination(endDate=2025-01-29))`
   on screen. `singularity-todo/screenshots/12-recurrence-raw-tostring.webp`.
2. **A task with only a title renders six empty sections** — Related tasks, Checklist, Subtasks,
   Attachments, Time, Logbook — each with its own hint sentence, spread over ~2 screens.
   `03-task-detail-empty-sections.webp`, `04-task-detail-bottom-empty.webp`.
3. **There is no way to set a reminder on a task.** The intents exist, the read model carries
   `reminders`, and no composable draws them (`TaskDetailUiState.kt:51` is written and never read).
4. **Filled attributes are painted `primaryContainer` (#46709E) with `onSurface` text** — 3.67:1,
   below WCAG AA for 16sp body text — while the description placeholder is 2.06:1. Measured in
   `comparison/visual-analysis.md`.
5. **The keyboard hides the field being typed into.** On the checklist "Add item" field the IME
   covers the input and the top app bar collides with the status bar.
   `13-checklist-add-keyboard-covers-field.webp`.
6. **Create and edit are two different products.** Create is a full-screen form with a Save button
   and a "Title is required" error on a pristine form; edit is a document that autosaves with no
   Save button. `19-create-screen.webp` vs `02-task-detail-minimal-top.webp`.
7. **The screen has no title in its app bar** — it says `Tasks` while showing `Купить хлеб`;
   the only identification is the field itself. `02-task-detail-minimal-top.webp`.
8. **Language is mixed inside one screen**: `Next up`, `Medium priority`, `Add tags`, `Not pinned`
   next to `Добавить оценку`, `Добавить дату`, `Введите описание задачи...`. The overflow menu is
   Russian. `02-task-detail-minimal-top.webp`, `05-overflow-menu.webp`.
9. **SingularityApp keeps every optional parameter in one neutral row and hides the rest behind
   "Больше параметров"**, where each parameter can be pinned. We show all of them, always.
   `singularity-app/screenshots/09-more-parameters.webp`.
10. **SingularityApp creates a task from an inline composer** — one field, date/time chips, project,
    tags, expand, send — not from a separate screen.
    `singularity-app/screenshots/11-quick-add-composer.webp`.

---

## What SingularityApp does that is worth copying (and what is not)

Copy the *idea*, not the pixels — it is a dark-only Flutter app and we are a light-first Compose app.

| Worth adopting | Worth adapting | Do **not** adopt |
|---|---|---|
| One neutral row per attribute, uniform 48–50dp | Grouping date/reminder/repeat/deadline into one card | Explicit "Сохранить" as the only commit path (they also autosave — verified) |
| "Больше параметров" instead of empty sections | Date sheet with Сегодня / Вечером / Завтра / Когда-нибудь | Dark-only palette |
| Created/updated footer | Nested time sheet over date sheet | 3 priority levels (we have 5 and use them) |
| Inline quick-add composer with attribute chips | Drag handle to reorder checklist items | Truncating long project names without ellipsis |
| Rich-text description editor in its own sheet | Priority confirmed with ✓ | Task detail as a bottom sheet that becomes a screen while scrolling |

Full reasoning: `comparison/ux-matrix.md`.

---

## Method in one paragraph

`adb` on device `0O65321F32204DD5` (Realme RMX3938, 720×1600 px, density 320 → **1dp = 2px**,
Android 15 / SDK 35). Singularity Todo `0.1.0+g29ccc0964fb7` and SingularityApp `10.23.0`, both on
the same imported profile (`singularity_backup_2026-11-08.json`: 12 026 tasks, 132 projects,
2 588 checklist items). Singularity Todo exposes a full accessibility tree, so its rows are measured
from `uiautomator dump` bounds; SingularityApp is Flutter with semantics off, so its geometry is
measured from screenshot pixels. Every number in these documents is px from one of those two
sources, divided by 2 to get dp. Details and caveats: `environment.md`.

---

## Research artefacts

```
environment.md                     device, versions, measurement method
test-data.md                       what was imported, what the import lost, fixtures, side effects
singularity-todo/
  screen-inventory.md              screens, states, screenshot index
  interactions.md                  interaction catalogue + defects observed
  screenshots/                     26 images
singularity-app/
  screen-inventory.md
  interactions.md
  screenshots/                     11 images
comparison/
  ux-matrix.md                     scenario-by-scenario
  ux-findings.md                   UXF-01…UXF-24, P0…P3, with evidence
  visual-analysis.md               typography, spacing, colour, contrast, language
implementation/
  current-architecture.md          what the code actually is today (file:line)
  adr-compliance.md                decision → implemented?
  target-ux.md                     the target screen, state by state
  wireframes/                      annotated wireframes
  implementation-plan.md           workstreams, order, acceptance criteria
testing/
  coverage-gaps.md                 behaviour → test that exists / must be written
  acceptance-criteria.md           per-workstream DoD
```

`.tools/shot.sh` is the screenshot helper used for every image.

---

## Limitations of this study (read before quoting a number)

- **One device, one profile, one theme (light), one font scale.** Row heights are verified at
  360×800dp only.
- **SingularityApp's accessibility tree is empty** (Flutter semantics off). Its row geometry and
  text sizes come from pixels, so its *font* sizes are inferred from ink height (±1–2sp), while its
  *layout* values (48dp rows, 8dp gaps) are exact.
- **The import is lossy in ways that shape what the screen can show** — no reminders, no attachments,
  9 637 of 9 658 completed tasks read as active, `deadline` mapped to a field the detail does not
  render. Every screen showing "empty" here is genuinely empty in the database. See `test-data.md`.
- **Cyrillic cannot be typed with `adb shell input text`** (throws on this ROM), so search-driven
  scenarios used Latin substrings of real titles.
- Two data side effects from the session are recorded with exact ids in `test-data.md`.
- SingularityApp's desktop build was **not** examined; all statements about it are about the
  Android 10.23.0 build.
