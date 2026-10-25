# Tasks — task-series-invariants

## Phase 0 — decisions before code

- [x] **shared/**: record the two decisions as ADRs — the series says one thing
      about the work, and a series can be split and joined.
      Test: the repository's ADR status gate passes.
- [x] **shared/**: the OpenSpec change that states the behaviour in scenarios.
      Test: `openspec validate --all --json --strict` passes.

## Phase 1 — the rules, as pure functions

- [x] **shared/**: `TaskSeriesInvariants` — the shared fields of a task, the
      fields the members disagree on, the alignment, the members that have to be
      written, and the batch checks.
      Test: `TaskSeriesInvariantsTest` — the values that must agree; the
      divergence; the alignment keeps the occurrence fields; only the members
      that differ are realigned; a batch with two series ids is not one series.

## Phase 2 — the write path

- [x] **shared/**: `realignSeries` — after a single-row write, the other rows of
      the series take the written row's shared fields, and the rows that already
      agree are not written.
      Test: the repository contract test — a rename of one occurrence realigns
      the series; a completion does not write the others.
- [x] **shared/**: the guards — `createSeries` and `updateAll` refuse a batch
      that is not one series, and align a batch that drifted apart.
      Test: the repository contract test — a batch with two series ids fails and
      writes nothing.

## Phase 3 — the pull path

- [x] **shared/**: a row that arrives from another device realigns the local
      series to it, scoped by the row's own user.
      Test: the repository contract test — an incoming row with another title
      realigns the local series; another user's row does not touch this
      profile's rows.

## Phase 4 — leaving and joining

- [x] **shared/**: `DetachTaskFromSeriesUseCase` — the occurrence keeps its own
      fields and loses the series id; a series of one becomes a task that stands
      alone.
      Test: `DetachTaskFromSeriesUseCaseTest`.
- [x] **shared/**: `MergeTaskSeriesUseCase` — the series the user is looking at
      keeps its id and its fields, the other series takes them, and the dates of
      both survive.
      Test: `MergeTaskSeriesUseCaseTest`.

## Phase 5 — the surfaces

- [x] **shared/**: the task detail's series card offers "Оставить отдельно",
      wired through the intent → slot → repository path.
      Test: the detail screen's test tag is registered, and the coordinator graph
      test sees the new dependency.
- [x] **shared/**: the agenda's selection panel offers to join two series when
      exactly two series are selected.
      Test: the agenda's test tag is registered, and the view model test covers
      the merge handler.
- [x] **shared/**: the DI modules carry the two use cases.
      Test: the Koin graph compiles, and the graph test sees the new bindings.

## Phase 6 — the gates

- [x] **shared/**: detekt, the JVM tests, the coverage floor and the static
      gates pass.
      Test: the CI run on the branch is green.
- [x] **shared/**: the issue registry carries this change.
      Test: `python3 scripts/check-gate-registries.py` passes.
