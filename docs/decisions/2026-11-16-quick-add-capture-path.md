---
title: "Quick Add is deferred; the Create screen becomes capture-first"
date: 2026-11-16
status: deferred
tags: ["tasks", "ux", "quick-add", "create", "decision"]
issue: 2886
---

# Quick Add is deferred; the Create screen becomes capture-first

## Context

Issue #2886 asked whether Singularity Todo needs a lightweight, title-first
**Quick Add** entry point next to the existing full Create/Edit experience, and
asked for an explicit implement / defer / reject decision backed by the current
code and concrete capture scenarios. It explicitly did not authorise building a
second task editor.

What the repository already has:

- `TaskCreateScreen` renders the shared `TaskEditorContent` through
  `TaskCreateEditorContent`; Task Detail renders the same renderer, but Create
  and Detail keep separate state owners.
- `TaskCreateViewModel` owns a `TaskDraft`, autosaves it through `DraftStore`,
  validates the title with `TaskTitleValidation`, and persists through
  `CreateTaskFromDraft`.
- `CreateTaskFromDraft` already returns `Either<AppError, TaskId>` — the created
  id is available to the state owner today; only `TaskCreateViewModel.persist`
  throws it away (`Either.Right(Unit)`).
- The create route carries contextual seeds: `initialDueDate`, `initialProjectId`,
  `initialTagIds`, `draftKey`.
- The reference app captured in the #2871 research does **not** use a form for
  capture: its create screen is "a composer docked above the IME — one text
  field, no Save button", with date/time/tag/project chips and an expand arrow
  to the full editor (`docs/research/task-detail-ux/singularity-app/screen-inventory.md` §9).
- The house already ships title-first capture twice: the notes list
  (`QuickAddRow`, ADR `2026-09-09-notes-quick-add.md`) and the project detail
  (an inline composer that creates through `CreateTaskUseCase` with a title and
  the project). Tasks are the one entity whose capture goes through a form.

## Evidence

### Measured on a device

Device: Motorola RMX3938, 720×1600 @320dpi (360×800 dp) — the same device the
#2871 research used. Installed build `9a892e61`; the title-row focus logic and
the save bar are byte-identical to HEAD (`d9bbe7ef`), so the observations
describe the current branch.

1. **The title does not take focus on open.** With the create screen open and
   untouched, `mInputShown=false` — no keyboard, no caret. The user taps the
   title field before typing. This is deliberate in the code: focus follows a
   tap, never the field's existence (`TaskTitleRow`, `focusRequested`).
2. **With the keyboard open, the Save button is behind the keyboard.** IME
   frame `[0,993][720,1600]`; the Save button's bounds are
   `[32,1216][688,1320]` — entirely inside the keyboard. The save bar carries
   no `imePadding()`, and the app is edge-to-edge (`enableEdgeToEdge()`).
3. **One back press dismisses the IME and keeps the screen** (`mInputShown`
   goes false, the create screen stays; `ImeAwareBackHandler`). The Save
   button then sits at the same bounds and is reachable.

Consequence for a title-only capture from Inbox: **four taps** — FAB, title,
back (dismiss the IME), Save. Two of them are not needed for the task to exist.

4. **A capture meets eight fields.** The hierarchy dump of the create screen
   lists title, description, checklist, attachments, priority, estimate, due
   date, start date.

### What could not be measured

**How often users capture rather than author is unknown.** The `Analytics` port
is a no-op (`core/analytics/Analytics.kt`), and the device database holds five
tasks, all of them Maestro artifacts (`pf Full task with all fields`,
`pg Task from Today tab`). There is no usage evidence in the repository, so no
frequency is claimed here — a guessed frequency cannot justify a new surface.

### Entry-point inventory

| Entry point | Opens | Seeds | After save |
|---|---|---|---|
| Shell FAB — Today | `TasksStartRoute.Create()` | `today` via the graph's `initialDueDate` | back to the caller |
| Shell FAB — Inbox / Upcoming / Blocked / Stagnant / Overdue | `Create()` | none | back to the caller |
| Shell FAB — project drill-down | `Create(projectId)` | project | back to the caller |
| Shell FAB — tag / multi-tag drill-down | `Create(tagIds)` | tags | back to the caller |
| Agenda — section create | `Create(draftKey = agendaSection(id))` | section draft | back to the caller |
| Agenda — multi-date pick | `Create(draftKey)` | dates in the draft | back to the caller |
| Calendar — single day | `Create()` + `initialDueDate` | that day | back to the caller |
| Calendar — multi-day pick | `Create(draftKey)` | days in the draft | back to the caller |
| Desktop — "New Task" menu / Ctrl+N | `Create()` | none | back to the caller |
| Projects — project detail inline composer | inline field, `CreateTaskUseCase` | title + project | stays in place |
| Notes — inline `QuickAddRow` | inline field | title | opens the note editor |

Every one of these already carries its context into the editor, and the
return-to-source-tab contract is covered by `Maestro/flows/tasks/16-return-to-source-tab.yaml`.

### Scenario matrix

| Scenario | Current flow (measured) | Quick Add candidate | Verdict |
|---|---|---|---|
| 1. Inbox capture | FAB → tap title → type → dismiss IME → Save; 4 taps, no context | FAB → sheet → type → Add; list stays visible | Candidate is not cheaper in taps; the gap is the two extra taps, not the surface |
| 2. Today capture | as above; date pre-filled and visible in the editor | same + a date chip | Same conclusion; the prefill already shows the context |
| 3. Project capture | as above; project row visible in the editor | same + a project chip | Same |
| 4. Tag-scoped capture | as above; tags row visible | same + tag chips | Same |
| 5. Calendar capture | `Create()` + `initialDueDate`; date visible in the editor | sheet + date chip | Same; a chip and an editor row carry the same information |
| 6. Capture then enrich | Save → back to the list → tap the task → Detail (1 tap) | Save → Detail (0 taps) | The candidate saves one tap, at the cost of a new route and a new event contract |
| 7. Cancel / interruption | Autosaved draft survives process death under a scoped key; the back guard asks before discarding | own draft key, own cleanup policy | The candidate adds a second draft to keep apart from the full-create draft — a risk the issue itself names |

## Decision

**The separate Quick Add surface is deferred.** No new composer route, no second
state machine, no duplicated validation, no second draft key.

**The capture gap is closed inside the existing Create flow**, as a bounded
implementation issue of its own:

1. **Title-first focus.** The create screen focuses the title when it was
   opened as a capture — that is, when no `draftKey` was passed. A `draftKey`
   means a prepared draft (agenda section, agenda multi-date, calendar
   multi-date): those flows scroll the editor to a row, and the code already
   warns that an IME opening under that scroll changes what the scroll means.
   The rule "`draftKey == null` ⇒ focus the title" needs no new route field,
   no new seed field and no new plumbing, and cannot drift from the seeded
   flows: the same condition that says "this editor has a prepared draft" says
   "do not steal the focus".
2. **The Save button stays reachable with the keyboard open.** `TaskSaveBar` is
   used by `TaskCreateScreen` only, so giving it `imePadding()` is a
   create-only change.
3. **Everything else stays as it is.** Same `TaskDraft`, same
   `TaskTitleValidation`, same `CreateTaskFromDraft`, same seeds, same
   autosave, same discard guard, same return-to-the-caller after save.

The post-save answer for this decision is therefore the current one: **the user
returns to the source list**. Enriching the new task costs one tap on it in
that list. Opening the new task's Detail instead would need the created
`TaskId` to travel out of `TaskCreateViewModel` (it is available today —
`CreateTaskFromDraft` returns it — but is discarded), and it would change the
return-to-source-tab contract that Maestro pins.

## Rationale

**The measured gap is two defects, not a missing surface.** Everything the
measurement found is attributable to two properties of the existing screen: the
title does not take focus, and the save bar sits under the IME. Fixing those
removes 100% of the measured cost of capture. A second surface would remove
the same two taps while adding a route, a state owner, a draft key to keep
apart from `task_create_draft`, a seed contract of its own, an "expand to the
full editor" path back into the editor, and a test matrix for all of it.

**A title-first surface is already the house pattern — inline, not a route.**
The notes list and the project detail both capture with a title-only field
in place, because that is where the user already is. The projects feature shows
what a second capture path costs when it is not shared: the project detail
creates tasks through `CreateTaskUseCase` directly, outside the draft path
entirely, which is exactly the duplication the issue asks not to create.

**There is no evidence for a frequency claim.** The one question the issue
calls load-bearing — how often capture happens versus authoring — has no
answer in the repository, and the device data is test residue. Deferring is
what the evidence supports; building a new surface on a guess is what it does
not.

**The reference app's composer is a different information architecture, and
that is not this issue's decision.** SingularityApp keeps the list visible and
docks the composer above the IME; adopting that means changing how the create
flow relates to the lists, which is a programme-level IA question and not a
P2 optional add. Deciding it here would also put it in the way of the #2872
Task Detail waves, which this issue must not do.

**Option C is rejected outright.** A second creation implementation would
duplicate the validation, the task construction, the draft recovery, the error
handling and the tests that `TaskCreateViewModel` and `CreateTaskFromDraft`
already own.

## Consequences

### Positive

- The two avoidable taps in every capture flow disappear, for every entry
  point, without a new surface.
- No new concepts: no route, no state machine, no draft key, no duplicated
  validation, no second screen to keep in step with the editor.
- The existing context contracts (Today date, project, tags, calendar day)
  keep working exactly as they are.

### Negative / risks

- The create screen still shows eight fields for a title-only capture. That
  cost is visual, not interactive, and it is an assumption rather than an
  observation — no user study has been run.
- Rapid multi-capture stays "FAB → type → Save → list". The Quick Add
  candidate would not have been faster here either: it also leaves the list.
- Autofocus interacts with the flows that scroll the editor. The
  `draftKey == null` rule keeps those flows out of it, and the regression
  suites for the agenda and calendar multi-date paths are the guard.

### Non-goals

- No second create route, sheet, or dialog.
- No new draft key, no second `TaskDraft`, no second title validation.
- No change to the post-save navigation contract.
- No analytics, and no instrumentation of the create flow.

### Trigger to reopen

Reopen #2886 when a measurement shows that the *surface* is the problem, not
the two taps. The measurement is available today and needs no new code: over
the tasks a user has created, count the share that carry **only a title** —
empty description, no checklist, no recurrence, no priority, no estimate, no
attachments. If that share is high, the authoring fields in the create screen
are dead weight for most captures, and a compact composer earns its cost. If
the share is low, users do author in the create screen and the full editor is
the right surface.

Until such a measurement exists, the honest position is the one this record
takes: the flow was made faster; whether it should also be made smaller is
still unknown.

## Links

- Issue #2886 (the decision), #2872 (parent programme), #2871 (the comparison
  research).
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskTitleRow.kt`
  — focus follows a tap, never the field's existence.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskSaveBar.kt`
  — used by the create screen only; no `imePadding()` today.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/CreateTaskFromDraft.kt`
  — already returns the created `TaskId`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt`
  — the single state owner; `persist` drops the created id.
- `shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt` —
  the FAB seeds; `docs/decisions/2026-09-09-notes-quick-add.md` — the house
  precedent for title-first capture.
- `docs/research/task-detail-ux/singularity-app/screen-inventory.md` §9 — the
  reference app's composer.
