# Implementation Plan: Plans Timeline and Projects Menu

**Feature**: `specs/016-plans-timeline-projects-menu`
**Spec**: [spec.md](./spec.md)
**Research**: [research.md](./research.md)
**Date**: 2026-10-26
**Branch**: `016-plans-timeline-projects-menu` — *derived by spec-kit from the feature directory; no git branch was created, because `.specify/extensions.yml` registers no `before_specify` hook. The checkout stays on `gazon1/pearlside`.*

## Summary

The Plans tab stops being a project list and becomes a chronological timeline of upcoming work: one
section per day, week or month, each holding the tasks of that period, with the granularity
switchable by the user and remembered between launches. The project hierarchy moves out of the tab
bar and into the app menu, where it is an expandable tree. The Calendar leaves the bottom navigation
bar and joins the menu — on Android as an entry of the bottom sheet, on Desktop as an entry of the
navigation drawer.

The technical spine is three things.

**A new feature, `feature/plans`, owns the timeline.** It is not a new agenda preset, because the
agenda's sections are a fixed, saved list and the timeline's sections are a function of today's
date — the agenda has no concept of a horizon. The timeline generates its periods from the
granularity, assigns each task to the single period that contains its due date, and asks the data
layer for exactly the range it is about to show.

**One vocabulary for a task row.** The badge enum and the row wrapper that the agenda renders are
promoted out of the agenda feature into the tasks feature, so the agenda and the timeline speak the
same language about a task and neither can drift from the other.

**One menu, two drawings.** The menu model grows a kind of entry that is not a destination — the
projects tree — and both platforms render the same tree composable, so "the same menu on Android
and on Desktop" is true by construction rather than by review.

Two parts, in this order: the timeline first, then the menu. In the other order the Plans tab would
be the timeline with the project list stranded, because the list's only entry point today is that
tab.

## Technical Context

| Field | Value |
|---|---|
| Language | Kotlin 2.x, commonMain + androidMain + jvmMain (no iOS) |
| UI | Compose Multiplatform, Material 3 |
| State | MVI — `MutableStateFlow` + `scope.launch { }.collect {}`; `stateIn` and `viewModelScope` are forbidden |
| DI | Koin 4.x, pure DSL; per-domain `*DiModule.kt` aggregated by `core/di/Modules.kt` |
| Persistence | Room (tasks, projects), DataStore (settings) |
| Navigation | Nav3, `AppDestination` + `ScreenFamily` |
| Async | Kotlin Flow |
| Testing | Fakes from `test/fakes/`, virtual time via `advanceUntilIdle()`, no mocks, no `delay(N)` |
| Platforms | Android + JVM Desktop — both must carry the feature |

## Constitution Check

| Principle | Requirement | Status | Evidence |
|---|---|---|---|
| I. KMP Architecture | Android + JVM only; UI in Compose; Flow for async; no `java.io.File` in commonMain | PASS | The timeline's logic is pure Kotlin in commonMain; both shells consume the same composables |
| II. ADR Before Code | Architectural decisions over a week of effort need an ADR; validate the corpus after every change | PASS | Part 0 writes `docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md` and runs `singularity-adr validate` |
| III. Validate Early and Often | Non-zero exit from a gate is a broken contract | PASS | Every part ends with the narrowest gate that covers it; the traceability gates are run before the plan is closed |
| IV. Immutable Historical Record | Accepted ADRs are never edited | PASS | The ADR is new; nothing accepted is touched |
| V. CI Gates | All ADR changes pass before merge | PASS | `singularity-adr validate` runs in the ADR task |
| VI. MVI ViewModels | Canonical pattern; no `stateIn`, no `viewModelScope`, no `runBlocking` | PASS | `PlansViewModel` and `ProjectsTreeViewModel` follow `core/ui/MviViewModel.kt`; the injected scope is used |
| VII. Test Fakes | Fakes, not mocks; virtual time | PASS | The grouper is a pure function — no fakes needed; the ViewModel tests use the repository fakes |
| VIII. OpenSpec Workflow | proposal → specs → design → adr → tasks → implement → converge; architecture changes need ADR *and* spec | PASS | Part 0 creates `openspec/changes/plans-timeline-and-projects-menu/` with proposal, specs, design, adr and tasks |
| IX. Acceptance Scenario Runs the Production Path | A task closes only when its scenario runs on the production wiring | PASS | Every scenario names the screen the user touches; the carriers are UI tests and Maestro flows, not unit tests behind a fake graph |

**Complexity gate**: this change touches navigation (the Calendar leaves the tab list), the menu
model (a new kind of entry), the DI graph (a new feature module) and the data layer (a new settings
repository). Three of the four are named in the constitution's "an ADR IS required when" list. The
ADR is therefore mandatory, and Part 0 writes it before any code.

**Post-design re-evaluation** (after the research settled the design): the gate holds. The
research removed the two changes that would have needed an ADR of their own — the timeline does not
change the agenda's definition model or its evaluator, and the tree does not add a new navigation
key type. What remains is one ADR for the feature as a whole.

## Project Structure

### Documentation this change owns

```text
openspec/changes/plans-timeline-and-projects-menu/
├── proposal.md                       # what and why
├── design.md                         # cross-cutting: new module, new menu kind, granularity flow
├── adr.md                            # references the ADR slug, no duplicated rationale
├── tasks.md                          # every behaviour task names its test
└── specs/
    ├── plans-timeline/spec.md        # ADDED Requirements — the timeline
    └── navigation-and-menu/spec.md   # MODIFIED Requirements — the menu and the tab list

docs/decisions/
└── 2026-10-26-plans-timeline-and-projects-menu.md   # the ADR

specs/016-plans-timeline-projects-menu/
├── spec.md                  # the specification (done)
├── plan.md                  # this file
├── research.md              # the decisions (done)
├── data-model.md            # the entities (done)
├── quickstart.md            # how to prove it (done)
├── checklists/requirements.md
└── contracts/
    ├── plans-timeline.md
    └── projects-menu-tree.md
```

### Source this change touches

```text
shared/src/commonMain/kotlin/com/singularity/todo/
├── feature/plans/                          # NEW — the timeline
│   ├── PlansDiModule.kt
│   ├── domain/
│   │   ├── model/PlansGranularity.kt       # Day / Week / Month, with the horizon
│   │   ├── model/TimelinePeriod.kt         # one period: id, start, end
│   │   ├── model/TimelineSection.kt        # a period plus the tasks it holds
│   │   ├── logic/TimelinePeriods.kt        # periods for a granularity, from today
│   │   ├── logic/TimelineGrouper.kt        # tasks into sections — pure
│   │   └── port/PlansSettingsRepository.kt # the granularity preference
│   ├── data/DataStorePlansSettingsRepository.kt
│   └── presentation/
│       ├── viewmodel/PlansViewModel.kt
│       ├── screen/PlansScreen.kt
│       ├── screen/PlansContent.kt
│       └── components/PlansSectionList.kt
│
├── feature/tasks/domain/                  # CHANGED — the shared row vocabulary
│   ├── model/TaskRowItem.kt               # NEW — promoted from AgendaRowItem
│   ├── model/TaskBadge.kt                 # NEW — promoted from AgendaBadge
│   └── logic/TaskBadgePolicy.kt           # NEW — promoted from AgendaBadgePolicy
│
├── feature/agenda/                        # CHANGED — consumes the promoted vocabulary
│   ├── domain/model/AgendaUiState.kt      # RenderedSection now holds TaskRowItem
│   ├── domain/logic/AgendaBadgePolicy.kt  # deleted, moved
│   ├── domain/selector/SelectorBadge.kt   # updated to the promoted enum
│   └── presentation/screen/AgendaContent.kt
│
├── feature/projects/                      # CHANGED — the tree
│   ├── domain/model/ProjectTreeNode.kt    # NEW
│   ├── domain/logic/ProjectTreeBuilder.kt # NEW — builds the tree from projects
│   ├── presentation/viewmodel/ProjectsTreeViewModel.kt   # NEW
│   └── presentation/components/ProjectTreeSection.kt      # NEW — shared by both shells
│
├── feature/nav/
│   ├── AppDestination.kt                  # CHANGED — Calendar leaves tabs, joins menuEntries
│   └── ScreenFamily.kt                    # CHANGED — a family for the timeline
│
├── shell/
│   ├── MenuBottomSheet.kt                 # CHANGED — a Projects section, a Calendar entry
│   └── FabActionResolver.kt               # CHANGED — the Plans FAB creates a task
│
└── core/ui/TestTags.kt                    # CHANGED — tags for the tree and the control

shared/src/jvmMain/kotlin/com/singularity/todo/shell/
└── DesktopShellNav3.kt                    # CHANGED — the drawer renders the tree

shared/src/commonMain/kotlin/com/singularity/todo/core/settings/
└── SettingsNamespace.kt                   # CHANGED — a namespace for the plans feature

scripts/src/traceability/scenarios/        # CHANGED — the eighteen scenarios
├── plans/PLANS-01.yaml … PLANS-10.yaml
├── menu/MENU-01.yaml … MENU-06.yaml
└── nav/NAV-01.yaml, NAV-02.yaml
```

### Structure decision

`feature/plans` is a separate module rather than a screen inside `feature/agenda`, because the two
screens have different owners: the agenda's screen is built around saved views — a dropdown of
stored definitions, a section editor, a selector composer — and the timeline has none of those. The
agenda's screen also cannot express the timeline's two moving parts: its definition is fixed when
the ViewModel is constructed, and the timeline's definition changes with the granularity and with
the day.

The row model is promoted rather than copied. `AgendaBadge` is an enum of six cases; two copies
would be two vocabularies, and the badge a user sees would depend on which screen they were on.
The badge policy already depends only on the tasks feature, so promoting it creates no cycle.

## Part sequencing

### Part 0 — The change record (OpenSpec + ADR)

**Status**: written during planning. The ADR and the OpenSpec change are design artefacts, and the
constitution's workflow puts them before the implementation rather than inside it.

- [x] Write the ADR: `docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md` — the timeline
      as a feature of its own, the row vocabulary promoted into the tasks feature, the menu gaining
      an entry that is not a destination, the Calendar leaving the tab list.
- [x] Run `singularity-adr validate` — non-zero exit is a broken contract.
- [x] Write `openspec/changes/plans-timeline-and-projects-menu/proposal.md`, `design.md`, `adr.md`
      (referencing the ADR slug), `tasks.md`, and the two capability specs under `specs/`.
- [x] Import the change into the registry: `python3 scripts/openspec-import-all.py`, which writes
      `.specify/issues/plans-timeline-and-projects-menu.yaml`.
- [x] Refresh the decisions digest: `./scripts/refresh-decisions-digest.sh`.

**Proves**: the constitution's ADR-before-code and OpenSpec-workflow principles are satisfied before
any code is written.

### Part A — The timeline

**Merged when**: the Plans tab shows the timeline; the granularity switches and persists; the
scenarios PLANS-01 … PLANS-10 are carried.

- [ ] Promote the row vocabulary: `AgendaRowItem` → `TaskRowItem`, `AgendaBadge` → `TaskBadge`,
      the badge policy → `computeTaskBadge`, all into `feature/tasks/domain/`. Update the nine files
      that use them. The enum keeps its six cases and their meanings.
- [ ] Add `PlansGranularity` with its horizon, `TimelinePeriod`, `TimelineSection`.
- [ ] Add `TimelinePeriods`: the periods for a granularity, from today. Week periods run Monday to
      Sunday; month periods are calendar months.
- [ ] Add `TimelineGrouper`: a pure function from tasks, granularity and today to sections. Overdue
      first, then the dated sections in order, empty periods dropped, completed tasks out.
- [ ] Add the settings port, the DataStore implementation, the namespace, and the contributor
      registration the settings machinery expects.
- [ ] Add `PlansViewModel`: granularity from the settings flow, tasks from the task repository,
      sections from the grouper, all combined and flattened without `stateIn`.
- [ ] Add `PlansScreen` and `PlansContent`: the section list, the granularity control, the per-section
      add control, the empty state.
- [ ] Bind the tab: `AppDestination.Plans` renders the timeline, the screen family is registered,
      and the FAB creates a task with today's date.
- [ ] Write the carriers for PLANS-01 … PLANS-10 and close the holes they leave in the ratchet.

**Proves**: PLANS-01 … PLANS-10 run against the production wiring, and the hole count falls by
thirty-six cells.

### Part B — The menu

**Merged when**: the projects are a tree in the menu on both platforms, the Calendar is an entry of
the menu and no longer a tab, and the scenarios MENU-01 … MENU-06 and NAV-01, NAV-02 are carried.

- [ ] Add `ProjectTreeNode` and the tree builder, using the repository's depth-first traversal so a
      cycle in the project hierarchy is caught rather than looped over.
- [ ] Add `ProjectsTreeViewModel` and the shared `ProjectTreeSection` composable.
- [ ] Give the menu a kind of entry that is not a destination, and render the tree section in the
      Android sheet and in the Desktop drawer from the same composable.
- [ ] Move the Calendar: out of `DestinationKind.tabs`, into `DestinationKind.menuEntries`, and into
      the Android sheet's own section list.
- [ ] Keep the full project list reachable from the Projects section of the menu.
- [ ] Write the carriers for MENU-01 … MENU-06, NAV-01 and NAV-02.

**Proves**: the two menus offer the same entries and the same tree, and the Calendar is one tap
away in both.

## Carry-forward from research

The decisions below bind the implementation. Each was settled against the working tree; the
reasoning is in `research.md`, and the files it cites are the evidence.

| Decision | Binds |
|---|---|
| D-01 — the timeline is its own feature | Part A creates `feature/plans`; it does not add an agenda preset |
| D-02 — sections are generated, disjoint by construction | The grouper owns the assignment; the renderer still keys rows `"{section.id}/{task.id}"` |
| D-03 — the row vocabulary is shared | The promotion in Part A; no second badge enum |
| D-04 — the timeline shows open work only | The grouper drops completed tasks |
| D-05 — the horizon belongs to the granularity and bounds what is shown | The ViewModel's horizons, and the whole task list it groups |
| D-06 — the granularity is a per-profile preference | The settings port, namespace and repository |
| D-07 — the Calendar leaves the tabs and joins the menu | Part B's navigation edits, on both platforms |
| D-08 — the menu gains an entry that is not a destination | Part B's menu model, and the shared tree composable |
| D-09 — the tree is built with the repository's traversal | The tree builder, and its cycle behaviour |
| D-10 — the full project list stays reachable | Part B's entry inside the Projects section |
| D-11 — two parts, timeline first | The order of the parts |
| D-12 — the FAB creates a task | Part A's `FabActionResolver` change |
| D-13 — the scenarios are declared now, carried by the implementation | The eighteen YAML files exist; the carriers land in Parts A and B |

## Complexity Tracking

| Item | Complexity | Resolution |
|---|---|---|
| A new feature module | Moderate | It follows the canonical CRUD pattern; the domain logic is pure and tested without a device |
| The row vocabulary promotion | Low | Nine files, a mechanical rename; no behaviour changes |
| The menu growing a non-destination entry | Moderate | One shared composable, used by both shells, keeps the parity requirement structural |
| The Calendar's move between tab and menu | Low | One list edit plus one entry in the Android sheet's own list; the screen itself is untouched |
| The granularity's persistence | Low | The feature-scoped settings pattern already exists and is copied |
| The eighteen scenarios and their carriers | Moderate | The scenarios are written; the carriers are ordinary UI tests and Maestro flows |

Nothing in this plan exceeds the complexity the constitution's gates can catch, and nothing needs a
decision record of its own beyond the one ADR Part 0 writes.
