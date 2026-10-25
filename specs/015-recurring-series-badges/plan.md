# Implementation Plan: Repeat as a materialised series, and the row badges

**Branch**: `gazon1/snook` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/015-recurring-series-badges/spec.md`

## Summary

Three independently mergeable parts. **A — Materialised series**: a repeating task
becomes a series of dated occurrence rows, so the next occurrences exist as real tasks
the user can see in Upcoming and complete on their own; closing an occurrence stops
moving that row's date, which is what makes the future visible at all. **B — Repeat
badge**: the list row shows that a task repeats — the icon already exists and has never
been reachable, because the row is built with the flag hardcoded to "no". **C — Project
badge**: the list row shows the task's project, by name and by the project's colour; the
project name does not reach the row today, so every agenda row currently prints the
literal "Без проекта" next to its date.

The technical spine is one idea carried over from the existing series decision: **a
series is several task rows that share one id**. A recurring task is the same shape —
several rows sharing a `TaskSeriesId` — with the difference that every row carries the
rule and the rows' dates are the rule's own occurrences. Nothing new is introduced at
the storage layer: `tasks.series_id` and `tasks.recurrence_rule` are both already
there, and the atomic multi-row writes (`createSeries`, `updateAll`) already exist.

The one decision that reaches beyond this feature is recorded in an ADR that **supersedes**
`2026-10-22-task-series-materialized-copies`: that ADR states that a series never holds
a rule, and a recurring series does.

Delivery order is B → C → A. The two badges are independent of the model change and
remove the "I cannot tell what this row is" half of the complaint on their own; A is
the larger change and carries the ADR.

## Technical Context

**Language/Version**: Kotlin 2.4 (Multiplatform), JVM toolchain 21; Android target bytecode JVM_11.

**Primary Dependencies**: Compose Multiplatform · Koin 4.x · Room (androidx.room3) · Nav3 · kotlinx.serialization · kotlinx.coroutines · kotlinx-datetime.

**Storage**: Room over SQLite. `tasks.series_id` (nullable, added by the hand-written migration 45 → 46) and `tasks.recurrence_rule` (JSON-encoded `RecurrenceSpec`) both already exist — **no schema change and no exported-schema bump is required by this feature.**

**Testing**: `:shared:jvmTest` (commonTest executes inside it) and `:desktopApp:test`; fakes from `test/fakes/`, never mocks; virtual time via `advanceUntilIdle()`; every test class tagged `fast`/`slow` with a sub-tag from the closed dictionary; desktop Compose UI tests for the row.

**Target Platform**: Desktop JVM and Android. No iOS in this project.

**Project Type**: KMP desktop + Android application.

**Performance Goals**: the agenda must not resolve a project per row — the name and colour are resolved once per list build (FR-033); a series write is one transaction, so a refused completion leaves nothing behind; materialising a window of at most ten rows must not cost more than the write amplification the series decision already accepts.

**Constraints**: no `stateIn` / `viewModelScope` / `runBlocking` in production · no pass-through use cases (the `PassThroughUseCase` detekt rule) · no `java.io.File` in `commonMain` · no mocks · the `UnitOfWork` transaction must wrap every multi-row write · no `MockK` except for outgoing calls · a series' shared fields must agree — `TaskSharedField.Recurrence` is one of them, and every materialised row carries the same rule so the agreement holds by construction.

**Scale/Scope**: 50 functional requirements; 1 new pure domain planner; 1 new pure domain window function; 1 new use case for the recurring close; 1 startup repair pass; 2 repository queries (the closed-projects lookup and the repair's own lookup); 2 new row-badge surfaces; 1 superseding ADR; 2 rewritten domain tests; new desktop Compose tests for the row.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 — see the second table below.*

| Principle | Requirement | Status | Evidence |
|---|---|---|---|
| I. KMP Architecture — Android + JVM Desktop only | No iOS; the change is shared | PASS | Every file touched is in `shared/src/commonMain`; the two entry-point hooks are the existing `main.kt` and `SingularityApp.kt` launch blocks. No `expect`/`actual` is added and no platform seam moves. |
| II. ADR Before Code | Architectural decisions above a week of effort need an ADR | PASS with an obligation | One ADR is planned and **written as part of this plan**: `2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md`. It supersedes `2026-10-22-task-series-materialized-copies`, whose clause "a series never holds a rule" this feature contradicts (research D1). |
| III. Validate Early and Often | `singularity-adr validate` after ADR changes | PASS | The validator runs as part of this plan and must pass before the tasks are closed. |
| IV. Immutable Historical Record | Accepted ADRs are superseded, never edited | PASS | The superseded ADR keeps its content untouched — only its frontmatter changes (`status: superseded` and the `superseded-by` link); the new ADR carries the `supersedes` link and quotes the clause it refines. |
| V. CI Gates | ADR + openspec validation before merge | PASS | `singularity-adr validate` and `openspec validate --changes` both run; the branch is pushed without `--no-verify`. |
| VI. MVI ViewModels | `StateFlow<SealedUiState>` + sealed Intent + injected scope; no `stateIn`, `viewModelScope`, `runBlocking` | PASS | The agenda keeps its `MviViewModel` shape; the projects flow joins the existing `flatMapLatest` chain as a `combine` (the pattern `CalendarViewModel.kt:96-113` already uses). The repair pass is a use case called from the existing launch hook, not from a ViewModel. |
| VII. Test Fakes | Fakes over mocks; virtual time | PASS | The recurring-close tests keep using the in-memory `TaskRepository` fake; no clock is read directly and no `delay(N)` is introduced. The window function is pure and is tested without a clock at all. |
| VIII. OpenSpec Workflow | Behavioural changes go through proposal → specs → adr → tasks | PASS with an obligation | This feature is behavioural, so an openspec change is required. The issue-registry record (`.specify/issues/<slug>.yaml`) is created as soon as the change exists. |

**Pre-Phase-0 verdict: PASS.** No unjustified violations, so `Complexity Tracking` stays empty.

### Post-design re-check

| Question | Answer |
|---|---|
| Did design introduce a new cross-cutting concern? | No. The series model, the shared-field rule, the atomic write path and the `UnitOfWork` transaction all already exist and are reused. The new code is a planner, a window function and a use case. |
| Did design change a contract between layers? | Yes, in two places, both additive and both inside the app: the row model gains a project colour and a derived repeat flag (`TaskUi`), and the row item gains the resolved project (`AgendaRowItem`). Neither is a port other features implement. |
| Did design require a change to a layer's contract with the storage? | No. `series_id` and `recurrence_rule` already exist; the only storage addition is a **query** that includes closed projects, which is not a schema change. |
| Does any part need an exception to a principle? | No. |
| **Post-design verdict** | **PASS** — unchanged. |

## Project Structure

### Documentation (this feature)

```text
specs/015-recurring-series-badges/
├── spec.md              # the feature specification (amended 2026-10-25, see its Change log)
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output: the fifteen decisions and what was rejected
├── data-model.md        # Phase 1 output: the series, the window, the row badge
├── quickstart.md        # Phase 1 output: how to verify the feature end to end
├── contracts/           # Phase 1 output
│   ├── recurring-series-window.md   # the window contract — the pure function and its guarantees
│   └── row-badges.md                # the row contract — what a badge is and what carries it
└── checklists/
    └── requirements.md  # the specification quality checklist (/speckit-specify)
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/singularity/todo/
├── feature/tasks/
│   ├── domain/
│   │   ├── logic/
│   │   │   ├── RecurringSeriesWindow.kt        # NEW — pure window function (research D2)
│   │   │   ├── RecurringSeriesPlanner.kt       # NEW — the row planner of a recurring series (D11)
│   │   │   └── TaskSeriesInvariants.kt         # EXTEND — the two-kind series rule (research D1)
│   │   ├── model/
│   │   │   ├── RecurrenceSpec.kt               # EXTEND — the CATCH_UP anchor comment (D10)
│   │   │   └── TaskSeriesPolicy.kt             # EXTEND — the rule's placement in the shared-field list
│   │   └── usecase/
│   │       ├── CompleteRecurringTaskUseCase.kt # CHANGE — close + materialise, never move (D4)
│   │       ├── CreateTaskFromDraft.kt          # CHANGE — the create trigger (D11)
│   │       ├── DetachTaskFromSeries.kt          # CHANGE — a leaving occurrence loses the rule, and so does the leftover (D15)
│   │       ├── MaterializeRecurringSeriesUseCase.kt  # NEW — the write that fills the window (D12)
│   │       ├── MergeTaskSeries.kt               # CHANGE — a join may not mix the kinds (D14)
│   │       └── RepairRecurringSeriesUseCase.kt      # NEW — the startup repair (D5, D13)
│   ├── data/
│   │   └── TaskRepositoryImpl.kt               # EXTEND — the repair's own lookup (D13)
│   └── presentation/
│       ├── model/TaskUi.kt                     # EXTEND — project colour, derived repeat flag (D7)
│       ├── viewmodel/slot/TaskEntitySlot.kt    # CHANGE — the edit trigger (D11)
│       └── components/list/
│           ├── TaskRowContent.kt               # CHANGE — open the meta-row gate (D7)
│           └── TaskMetaRow.kt                  # CHANGE — the project badge, the repeat badge (D7)
├── feature/agenda/
│   ├── domain/model/AgendaUiState.kt           # EXTEND — the resolved project on the row item (D6)
│   ├── presentation/viewmodel/
│   │   ├── AgendaDeps.kt                       # EXTEND — the projects repository (D6)
│   │   └── AgendaViewModel.kt                  # CHANGE — observe the projects alongside the tasks (D6)
│   └── presentation/screen/AgendaContent.kt    # CHANGE — derive the flags, pass the project (D6, D7)
├── feature/projects/domain/port/ProjectsRepository.kt   # EXTEND — a lookup including closed projects
├── core/database/Daos.kt                      # EXTEND — two queries, no schema change (D6, D13)
└── core/di/                                    # EXTEND — the new use cases are registered here

desktopApp/src/main/kotlin/com/singularity/todo/main.kt            # CHANGE — the repair hook (D5)
androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt  # CHANGE — the repair hook (D5)
docs/decisions/2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md  # NEW — supersedes 2026-10-22
```

## Part sequencing and what each part proves

| Part | What it delivers | Proves, on its own | Depends on |
|---|---|---|---|
| **B — Repeat badge** | The list row shows the repeat icon, derived from the task's own rule; the meta-row gates open so a recurring task with nothing else to show still shows it. | The user can tell a repeating task from a one-off task without opening it. | — |
| **C — Project badge** | The row carries the project's name and colour; the "Без проекта" placeholder leaves the row; the lookup is resolved once per list build. | The user can tell which project a row belongs to, and at a glance by colour. | — |
| **A — Materialised series** | A repeating task becomes a series of occurrence rows; the window is filled by a pure function; closing an occurrence no longer moves its date; the legacy repair runs at launch. | The future of a repeating task is visible in Upcoming and completable on its own. | B and C are independent of it; A does not need them. |

**B and C are the cheap half of the complaint** — "I cannot tell what this row is" —
and they are independent of the model change. **A is the expensive half** — "I cannot see
the future" — and it carries the ADR and the migration of existing data.

The order matters in one direction only: A changes the shape of a recurring row, and B
reads the recurring flag off that row. A before B would mean re-testing the badge; B
before A means the badge is already correct for both the old and the new shape.

### The two triggers of a materialisation

A rule can reach a row by two doors, and **both** have to materialise the series — the
review found that the plan named neither:

| Trigger | Path | What it does |
|---|---|---|
| **The create screen** | `TaskCreateViewModel.SetRecurrence` → `CreateTaskFromDraftUseCase` → `CreateTaskFromDraft` → `TaskSeriesPlanner.plan` | A draft with a rule and no extra dates materialises a series. **Today the rule is dropped on the way** — the planner sets `recurrence = null` on every row it writes, including a single-date draft (`TaskSeriesPlanner.kt:102`), so a task created with "repeat every week" came out with no rule at all. |
| **The task screen** | `TaskEntitySlot.SetRecurrence` → `mutate` → `UpdateTask` → `repo.update` | Setting a rule on a task turns it into a series with a materialised window. This is the only door today through which a rule reaches a row at all. |

**A rule and picked dates together:** the rule loses. The create screen already says so
(`RECURRENCE_DROPPED_MESSAGE`, `TaskCreateViewModel.kt:231-234`), and the design keeps
that answer rather than inventing a second one.

**The edit path already routes a shared change to the whole series.**
`TaskEntitySlot.mutate` sends a task that is already in a series through `updateSeries`
(`TaskEntitySlot.kt:176-182`), so the materialisation does not have to invent that
routing — it has to join it. A task that stands alone is the case the design adds:
setting a rule on it turns it into a series.

### The write shape

A materialisation that creates members **and** detaches one is two calls inside one
transaction — the reason is in `research.md` (D12): `createSeries` refuses a batch
carrying two series ids, and `updateAll` refuses to invent a member. **Either order is
safe**: the realignment that `repo.update` triggers returns at once for a row that
carries no series id, and the detached row is exactly that.

## Carry-forward from research

The decisions the Phase 0 research settled are recorded in `research.md` with their
evidence. Three of them change what the implementation must look like, and a reader of
this plan who skips the research should still not be surprised:

- **D1** — a recurring series is a series whose members carry the rule, and the two
  kinds of series (picked dates / the rule's own dates) never mix. This is the clause
  the superseding ADR refines, and it is why `TaskSeriesPlanner` keeps producing
  rule-free series while a new planner produces recurring ones.
- **D2** — the window is anchored at the current occurrence, not at today. The
  consequence is that **no scheduler is involved**: no daily job, no write on the read
  path, and the series converges without the calendar day entering the rule.
- **D4** — the three recurrence bases become uniform: every base closes the occurrence
  and materialises the next one, and the bases differ only in the anchor date. The
  `moveDueDate` call leaves the close path, and with it the defect that produced a row
  that was closed *and* dated in the future.
- **D10** — the base decides which date the rule is measured from: the due date for
  "from due date", the **completion** date for "from completion" — which is the
  picker's default. Closing late therefore moves the window of a "from completion"
  series, by exactly how late the close was. The move is a change of date on the rows
  that survive, so a future row's own reminder or checklist is not lost to it.
- **D14** — a series has two kinds, and the kind is decided by where its dates come
  from. The capability `task-series` owns the model of the series and describes the
  picked-dates kind, so the change carries a delta for it: the refusal at thirty dates
  stays for picked dates and becomes a rotation for a recurring one, and the
  rule-versus-picked-dates rule stays a conflict between two *intentions*.
- **D15** — an occurrence that leaves a recurring series loses the rule with the series
  id — and so does the row that stays behind when the series ends. Without it the row
  keeps the repeat badge and never repeats, and the launch repair would adopt it back
  into a series.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

The check passes without a violation, so this table stays empty. The one place where a
simpler alternative existed — keeping the one-row roll-forward — was rejected by the
user's own answer to the planning question, not by a principle.
