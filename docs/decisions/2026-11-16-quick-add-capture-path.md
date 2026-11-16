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

### Measured after the #2982 fixes

The same device, the same measurement commands, the debug build of this branch
(`./gw :androidApp:assembleDebug`) installed over the previous one with
`adb install -r` — same signature, app data kept:

1. **The title takes focus on open.** With the create screen opened from the
   FAB, `mInputShown=true` — the keyboard is up without a tap on the field.
2. **The Save button is above the keyboard.** IME frame `[0,993][720,1600]`;
   the Save button's bounds are `[32,705][688,809]` — the whole button is
   above the IME frame, where it was `[32,1216][688,1320]` before, entirely
   inside it.
3. **The task is saved with the keyboard open.** Typing the title and tapping
   Save produced exactly one row in the device database
   (`select … from tasks` — one title, one `id`); the IME closed and the screen
   returned to the list. No second task, no second row.
4. **The prepared-draft screens are unchanged.** The create screen opened with
   the agenda's prepared draft (`agenda_multi_date_draft`) still reports
   `mInputShown=false`: there the focus follows a tap, as intended.

So the capture is now **two taps** (FAB → Save), and it was measured on the
device rather than projected.

4. **The capture screen is not a two-field screen.** The device's hierarchy
   dump of the create screen lists eight rows on the first screen: title,
   description, checklist, attachments, priority, estimate, due date, start
   date. That is what fits above the fold, not the whole form: the create
   editor renders **twelve** fields in total — the eight above plus project,
   tags, recurrence and pin (`TaskCreateScreen` passes callbacks for exactly
   those four more; workflow-state, reminder, deadline and dependencies belong
   to the detail flow). An earlier draft of this record said "a capture meets
   eight fields"; that was the visible subset presented as the whole.

### Tap-count comparison

| Scenario | Taps | Basis |
|---|---|---|
| Current Create | 4 | **measured** on the device (FAB → title → back → Save) |
| Create after the #2982 fixes | 2 | **measured** on the device (FAB → Save; the keyboard opens by itself and Save sits above it) |
| Quick Add (hypothetical composer) | 2 | **projected**; the surface does not exist in the repository |

Method:

- Only taps are counted. Typing the title is not a tap, and neither is
  dismissing a keyboard that opened itself.
- Opening and closing the IME are counted only when the user has to do them
  for the action to be reachable. Today the user must close the IME to reach
  Save (one tap); after the fixes the IME opens on its own and stays out of the
  way (no tap).
- A value is labelled *measured* only when it was observed on the device, and
  *projected* when it follows from a change that is not implemented yet. The
  second row became a measured row when #2982 was built and re-measured; the
  third row cannot become one without building the surface.
- The seeds do not change the tap sequence: the Today, project, tag and
  calendar-day entries reach the same editor with a prefilled value, so they
  are the same four / two / two.

**Conclusion:** once the current Create is fixed, Quick Add has no proven
advantage in taps. Any other advantage (the list staying visible, the editor
not taking the screen) has to be argued separately.

### What could not be measured

**How often users capture rather than author is unknown.** The `Analytics` port
is a no-op (`core/analytics/Analytics.kt`), and the device database holds five
tasks, all of them Maestro artifacts (`pf Full task with all fields`,
`pg Task from Today tab`). There is no usage evidence in the repository, so no
frequency is claimed here — a guessed frequency cannot justify a new surface.

### Entry-point inventory

Every production path that creates a task, and what it opens:

| Entry point | Opens | Seeds | `draftKey` | Expected scenario |
|---|---|---|---|---|
| Shell FAB — Today | `TasksStartRoute.Create()` + graph `initialDueDate` | today | `null` | capture with a date |
| Shell FAB — Inbox / Upcoming / Blocked / Stagnant / Overdue | `Create()` | none | `null` | pure capture |
| Shell FAB — project drill-down | `Create(projectId)` | project | `null` | capture in a project |
| Shell FAB — tag drill-down | `Create(tagIds)` | one tag | `null` | capture with a tag |
| Shell FAB — multi-tag drill-down | `Create(tagIds)` | several tags | `null` | capture with tags |
| Calendar — single day | `Create()` + `initialDueDate` | that day | `null` | capture with a date |
| Calendar — multi-day pick | `Create(draftKey)` | the picked days, in a prepared draft | `calendar_multi_date` | prepared draft |
| Agenda — section create | `Create(draftKey)` | the section's prefill, in a prepared draft | `agenda_section(id)` | prepared draft |
| Agenda — multi-date pick | `Create(draftKey)` | the picked days, in a prepared draft | `agenda_multi_date` | prepared draft |
| Desktop — "New Task" menu / Ctrl+N | `Create()` | none | `null` | pure capture |
| Projects — project detail inline composer | an inline field, `CreateTaskUseCase` | title + project | — | capture in place |
| Notes — inline `QuickAddRow` | an inline field | title | — | capture in place |
| Search | nothing — the search screen does not create tasks | — | — | — |
| MCP server | its own path, not the UI | — | — | — |

`TasksNavigator.openCreate(initialDueDate)` exists but has no production
caller (the preview helper overrides it with a no-op); it is not an entry
point.

Two things the table says plainly:

- Every entry point that opens `TaskCreateScreen` already carries its context
  into the editor, and the return-to-source-tab contract is covered by
  `Maestro/flows/tasks/16-return-to-source-tab.yaml`.
- **Every capture entry point passes `draftKey == null`, and every entry point
  that passes a `draftKey` opens a prepared draft.** See the next section.

### What `draftKey` actually means

`draftKey` is not a scenario type. It is the key under which a *prepared
draft* was written to the `DraftStore` before the editor opened; the editor
restores that draft instead of starting empty, and `TaskCreateViewModel`
cleans up exactly that key on save or discard. Auditing every production
caller:

- Three flows pass a `draftKey`: the agenda section create
  (`TaskDraftKeys.agendaSection(id)`), the agenda multi-date pick
  (`AGENDA_MULTI_DATE`) and the calendar multi-date pick
  (`CALENDAR_MULTI_DATE`). Each of them **writes the prepared draft first**
  (`AgendaViewModel`, `CalendarViewModel`) and then opens the editor with the
  key of what it wrote.
- Every other flow passes `null`: the FAB variants, the calendar single-day
  entry, and the desktop menu / Ctrl+N.

So `draftKey == null` and "this is a capture flow" coincide today — but they
coincide because the prepared-draft flows are the only ones that prepare a
draft, not because the key says so. Three things must stay apart:

- the **absence of a draft id** — "there is nothing to restore";
- the **user's intent** — what the person came to do (unknowable from the
  route);
- the **type of the UI scenario** — capture vs. prepared draft.

**Decision:** do not add a route field. The rule "`draftKey == null` ⇒ the
editor focuses the title" is correct for every caller that exists, needs no
new plumbing, and cannot drift away from the seeded flows as long as the
mapping holds. **But it is an invariant, not a type**, and it is recorded as
one here: if a capture flow ever needs its own draft key (per-tab drafts, a
draft per list), the inference breaks and an explicit flag must replace it
before that happens. A regression gate pins the current mapping so the change
cannot land silently: `TaskCreateDraftKeyCallerTest` (`:shared:archTest`) fails
when a production caller starts carrying a draft key, and when a prepared-draft
flow stops carrying its own.

The historical reason the title does not grab the focus is narrower than it
looks. `TaskTitleRow` says the flows that fill the create editor "scroll it
into view first, and an IME opening under them changes what that scroll
means". The create editor does not scroll imperatively today — the only scroll
in it is the column's own, and `bringIntoViewOnFocus` follows a focus that
already happened. The prepared-draft flows are therefore left alone by the
focus rule as a conservative choice, not because a scroll exists.

### Scenario matrix

| Scenario | Current flow (measured) | Quick Add candidate | Verdict |
|---|---|---|---|
| 1. Inbox capture | FAB → tap title → type → dismiss IME → Save; 4 taps, no context | FAB → composer → type → Add; 2 taps, the list stays visible | No proven advantage in taps; the advantage, if any, is that the list stays visible |
| 2. Today capture | as above; the date is pre-filled and visible in the editor | the same, plus a date chip | Same conclusion; the prefill already shows the context |
| 3. Project capture | as above; the project row is visible in the editor | the same, plus a project chip | Same |
| 4. Tag-scoped capture | as above; the tags row is visible | the same, plus tag chips | Same |
| 5. Calendar capture | `Create()` + `initialDueDate`; the date is visible in the editor | a sheet plus a date chip | Same; a chip and an editor row carry the same information |
| 6. Capture then enrich | Save → back to the list → tap the task → Detail (1 tap) | Save → Detail (0 taps) | The candidate saves one tap, at the cost of a new route and a new event contract |
| 7. Cancel / interruption | the autosaved draft survives process death under a scoped key; the back guard asks before discarding | its own draft key, its own cleanup policy | The candidate adds a second draft to keep apart from the full-create draft — a risk the issue itself names |

### Re-evaluation after the fixes

The seven criteria from the issue, against the Create screen as it stands
after #2982:

| Criterion | Fixed Create | Quick Add candidate | Verdict |
|---|---|---|---|
| Quick title capture | the title is focused on open; typing starts at once | the same | equal |
| Keyboard access | the IME opens by itself | the same | equal |
| Saving without closing the keyboard | Save sits above the IME (`imePadding()`), measured | the same | equal |
| Keeping the project/date context | the seed is visible in the form (the date row, the project row) | the same information as a chip | equal in substance; a chip is the same fact drawn smaller |
| Number of actions | 2 taps, measured | 2 taps, projected | equal |
| The list stays visible | no — the editor takes the screen | yes | **the one real difference**, and it is visual |
| Access to the other fields | the whole form, right there | through an "expand" step | in the candidate's favour |

The comparison settles where the issue wanted it settled: after the fixes,
nothing that is measured gets better. The only difference left is that the
Quick Add candidate would keep the list on screen while the editor covers it —
and it buys that with an extra step to the fields the fixed Create already
shows, and with the second route, the second state owner and the second draft
key that the decision below refuses.

The reopen criterion ("the share of tasks that carry only a title") is still
unmeasured, and it is the only signal that could argue for the surface rather
than for the layout. Nothing in the repository measures it.

## Decision


**The separate Quick Add surface is deferred.** No new composer route, no second
state machine, no duplicated validation, no second draft key.

**The capture gap is closed inside the existing Create flow**, as a bounded
implementation issue of its own (#2982 — implemented, and measured on the
device):

1. **Title-first focus.** The create screen focuses the title when it was
   opened as a capture — that is, when no `draftKey` was passed. The rule is
   an invariant of today's callers, not a new route field; see
   "What `draftKey` actually means". The prepared-draft flows (agenda section,
   agenda multi-date, calendar multi-date) are left as they are.
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
removes the two measured frictions — the manual move of the focus onto the
title, and the manual dismissal of the keyboard to reach Save. It does not
remove the whole cost of capture: the twelve-field form is still there, and
that cost is neither measured nor disputed here. A second surface would
remove the same two taps while adding a route, a state owner, a draft key to
keep apart from `task_create_draft`, a seed contract of its own, an "expand to
the full editor" path back into the editor, and a test matrix for all of it.

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
  point, without a new surface. Implemented in #2982 and measured on the device:
  the capture is two taps, and Save is reachable with the keyboard open.
- No new concepts: no route, no state machine, no draft key, no duplicated
  validation, no second screen to keep in step with the editor.
- The existing context contracts (Today date, project, tags, calendar day)
  keep working exactly as they are.

### Negative / risks

- The create screen still shows twelve fields for a title-only capture, eight
  of them above the fold. That cost is visual, not interactive, and it is an
  assumption rather than an observation — no user study has been run.
- Rapid multi-capture stays "FAB → type → Save → list". The Quick Add
  candidate would not have been faster here either: it also leaves the list.
- Autofocus interacts with the prepared-draft flows. The `draftKey == null`
  rule keeps those flows out of it, and the regression suites for the agenda
  and calendar multi-date paths are the guard. If the mapping of entry points
  to `draftKey` ever changes, the rule has to change with it — the invariant
  is recorded above and pinned by `TaskCreateDraftKeyCallerTest`.

### Non-goals

- No second create route, sheet, or dialog.
- No new draft key, no second `TaskDraft`, no second title validation.
- No change to the post-save navigation contract.
- No analytics, and no instrumentation of the create flow.

### Trigger to reopen

Reopen #2886 when a measurement shows that the *surface* is the problem, not
the two taps. The cheapest available signal is a heuristic: over the tasks a
user has created, count the share that carry **only a title** — empty
description, no checklist, no recurrence, no priority, no estimate, no
attachments.

Its limits, stated so it is not read as more than it is:

- it is a proxy, not the user's intent — a task with only a title can be a
  deliberate one-line capture or a task the person intends to enrich later;
- a low share does not prove that the full editor is the right surface; it
  only means this particular signal does not argue for a compact composer;
- the heuristic needs no new code and no analytics, but a real answer needs a
  real sample — the repository has none, and the device data is test residue;
- it is one input among several, not a product requirement.

Until such a measurement exists, the honest position is the one this record
takes: the flow was made faster; whether it should also be made smaller is
still unknown.

## Links

- Issue #2886 (the decision), #2982 (the bounded implementation of the
  capture-first fix, pulled as PR #2988), #2872 (parent programme), #2871
  (the comparison research).
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskTitleRow.kt`
  — focus follows a tap by default; the create screen asks for it explicitly
  (`autoFocus`), and only when it has no `draftKey`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskSaveBar.kt`
  — used by the create screen only; carries `imePadding()`, so Save stays above
  the keyboard.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskDraftKeys.kt`
  — the prepared-draft keys; the only three flows that pass one.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TaskCreateDraftKeyCallerTest.kt`
  — the gate that pins the mapping between a draft key and the create editor.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/CreateTaskFromDraft.kt`
  — already returns the created `TaskId`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt`
  — the single state owner; `persist` drops the created id.
- `shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt` —
  the FAB seeds; `docs/decisions/2026-09-09-notes-quick-add.md` — the house
  precedent for title-first capture.
- `docs/research/task-detail-ux/singularity-app/screen-inventory.md` §9 — the
  reference app's composer.
