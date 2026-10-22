# Tasks — close-rule-consolidation

## Phase 1 — one owner for the rule

- [x] **shared/**: the blocking predicate has one owner; the second implementation is
      gone and its callers follow the first.
      Test: the domain tests for the predicate assert one behaviour, and the callers
      (badge, evaluator, health, project filter) use it.

## Phase 2 — the write refuses

- [x] **shared/**: completing a task is refused by the write itself while a
      prerequisite is open; the surfaces ask when it refuses.
      Test: a database-level test asserting the statement refuses, and a slot test
      asserting the question is asked and nothing is written.

## Phase 3 — the bulk close asks

- [x] **shared/**: a selection containing a blocked task closes nothing and names the
      task in the way.
      Test: a use-case test asserting the refusal, the named task and the untouched
      selection.

## Phase 4 — the cascade

- [x] **shared/**: closing a project lands as a whole, and reads only the project's
      tasks and their prerequisites.
      Test: a use-case test asserting the cascade and the refusal.

## Phase 5 — reopening brings the project back

- [x] **shared/**: taking a task back into work opens its closed project.
      Test: a use-case test asserting the project opens with the task, and that a task
      without a project changes nothing.

## Phase 6 — the contract says what it holds

- [x] **shared/**: the dependency set is called `blockedBy` in the domain, and the
      serialized name stays `dependsOn`.
      Test: a test pinning both the wire name and the absence of the domain name on
      the wire.

## Phase 7 — verification

- [x] **shared/** + **desktopApp/**: run the full gate set.
      Test: all four green.
