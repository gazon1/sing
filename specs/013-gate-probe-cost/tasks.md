---

description: "Task list for bounded gate liveness probing (spec 013)"
---

# Tasks: Bounded Gate Liveness Probing

**Input**: Design documents from `specs/013-gate-probe-cost/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/verdict-vocabulary.md, quickstart.md

**Tests**: Test tasks ARE included. The gate's entire purpose is converting silent failure into a red verdict, so an untested change to it is the defect class this repository exists to prevent (spec 012).

**Organization**: Tasks are grouped by user story. Each story is independently completable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- Exact file paths are given in every task

---

## Phase 1: Setup

**Purpose**: Establish the measurement baseline the change is judged against.

- [ ] T001 Record the pre-change baseline in `specs/013-gate-probe-cost/quickstart.md`: time `python3 scripts/check-gate-liveness.py` end-to-end, and the registry gate count from `python3 scripts/check-gate-liveness.py --list`. The measured baseline is 151.7 s serial sum / >280 s wall clock / 46 probeable — confirm it still holds rather than assuming.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Registry integrity helpers that every later phase depends on. No story can start until the registry is loaded consistently.

- [ ] T002 Add a helper in `scripts/check-gate-liveness.py` that resolves a `sgate <subcommand>` to its registry `GateSpec`, reusing the existing `_command_name()` and `gate_scripts()` from the 2026-10-15 heavyweight-filter fix rather than re-deriving the mapping.
- [ ] T003 [P] Add a test in `scripts/tests/test_check_gate_liveness.py` asserting the resolver returns a spec for a known gate and `None` for an unknown name.
- [ ] T004 Run `python3 scripts/check-gate-wiring.py --registry --registry-cross` and confirm it is green before any gate is removed — establishes that the cross-checks are working and will detect an incomplete removal.

**Checkpoint**: Registry resolution works and cross-checks are green. Foundation ready.

---

## Phase 3: User Story 1 - The two ADR gates are gone, and the registry stays consistent (Priority: P1) 🎯 MVP

**Goal**: Delete `closed ADRs keep their body`, `ADR frontmatter drift`, and `scripts/check-adr-immutable.py`, leaving no dangling registry entry, control, or live reference.

**Independent Test**: `python3 scripts/check-gate-liveness.py --list` shows neither gate; Parts F, J and H all pass; the static suite's wall clock drops by roughly the measured 120 s.

### Implementation for User Story 1

- [ ] T005 [US1] Remove the `GateSpec(name="closed ADRs keep their body", …, script=Path("scripts/check-adr-immutable.py"))` entry from `scripts/src/sgate/commands/_registry.py`
- [ ] T006 [US1] Remove the `GateSpec(name="ADR frontmatter drift", …, bash_func="adr_dry_run")` entry from `scripts/src/sgate/commands/_registry.py`
- [ ] T007 [US1] Remove the `'adr-immutable': Control(kind='sabotage', proves='scripts/check-adr-immutable.py', …)` entry from `GATE_CONTROLS` in `scripts/src/sgate/commands/_registry.py` — a control left behind proves a gate that no longer exists (FR-011)
- [ ] T008 [US1] Remove the `gate blocking "closed ADRs keep their body" sgate closed_adrs_keep_their_body` line and its surrounding comment block from `scripts/ci/static-gates.sh`
- [ ] T009 [US1] Remove the `gate advisory "ADR frontmatter drift" sgate adr_frontmatter_drift` line and its surrounding comment block from `scripts/ci/static-gates.sh`
- [ ] T010 [US1] `git rm scripts/check-adr-immutable.py`
- [ ] T011 [US1] Check whether removing the `adr_dry_run` gate leaves `adr_dry_run` in `scripts/ci/static-gates.sh` unreferenced; if so remove the function and its `export -f`, since a function nothing calls is the same dead surface this gate checks for
- [ ] T012 [US1] Update `docs/scripts/gate-registry.md` to drop both gates from any listing
- [ ] T013 [US1] Verify with `python3 scripts/check-gate-wiring.py --registry --registry-cross --reachability` that no cross-check is red
- [ ] T014 [US1] Verify with `python3 -m unittest discover -s scripts/tests` that no test imports the deleted script
- [ ] T015 [US1] Confirm with `grep -rn "check-adr-immutable" --exclude-dir=.git --exclude-dir=__pycache__ .` that remaining hits are closed ADRs and this feature's own documents only, which FR-012 requires to be left untouched

**Checkpoint**: US1 complete — ~120 s removed, registry consistent, cross-checks green.

---

## Phase 4: User Story 2 - The liveness gate has a wall-clock ceiling it keeps (Priority: P1)

**Goal**: An aggregate wall-clock budget, and an `unreached` verdict that names every gate the budget prevented from running.

**Independent Test**: Register a gate that hangs; the run completes within budget and its output names the unreached gates with `Not checked, not passed.`

### Tests for User Story 2 ⚠️

> Write these first; confirm they FAIL before implementing.

- [ ] T016 [P] [US2] Test in `scripts/tests/test_check_gate_liveness.py`: with an aggregate budget smaller than the serial sum, the run completes and populates the unreached set
- [ ] T017 [P] [US2] Test in `scripts/tests/test_check_gate_liveness.py`: `unreached` and `slow` produce distinct verdict values and distinct printed forms (FR: they must never share a code path)
- [ ] T018 [P] [US2] Test in `scripts/tests/test_check_gate_liveness.py`: with N hanging gates, runtime does not scale with N
- [ ] T019 [P] [US2] Test in `scripts/tests/test_check_gate_liveness.py`: `aggregate_seconds` below `per_probe_seconds` is rejected

### Implementation for User Story 2

- [ ] T020 [US2] Add `AGGREGATE_BUDGET_S` to `scripts/check-gate-liveness.py`, set above the measured post-US1 serial sum and below the current >280 s failure point; record the value and its derivation in the module comment
- [ ] T021 [US2] Add the `unreached` verdict to `scripts/check-gate-liveness.py` and the `UnreachedSet` accounting described in `specs/013-gate-probe-cost/data-model.md`
- [ ] T022 [US2] Record the aggregate start instant and check remaining budget before each probe in `scripts/check-gate-liveness.py`; stop and mark the remainder unreached when exhausted
- [ ] T023 [US2] Emit the `unreached` per-gate line exactly as specified in `specs/013-gate-probe-cost/contracts/verdict-vocabulary.md`, including the `Not checked, not passed.` clause
- [ ] T024 [US2] Append the `unreached` count to the summary line in `scripts/check-gate-liveness.py` without removing, reordering, or rewording any existing field
- [ ] T025 [US2] Ensure a killed probe leaves no lock or temp state that changes a subsequent run's outcome (FR-009) — document the check performed in the commit message

**Checkpoint**: US2 complete — the run is bounded and honest about what it did not reach.

---

## Phase 5: User Story 3 - A gate that is cheap stays provably cheap (Priority: P2)

**Goal**: Repair `is_registry_function()` so registry shell-function gates are counted rather than probed, and report the skip set on every run.

**Independent Test**: Every registry shell-function gate is counted, not probed; the skip set is named on every run including when empty.

### Tests for User Story 3 ⚠️

> Write these first; confirm they FAIL before implementing.

- [ ] T026 [P] [US3] Test in `scripts/tests/test_check_gate_liveness.py`: a gate whose `bash_func` is non-empty is classified as a registry function and NOT probed — this currently fails, because `is_registry_function()` matches `cmd.split()[0]` and every command begins `sgate`
- [ ] T027 [P] [US3] Test in `scripts/tests/test_check_gate_liveness.py`: a gate with no `bash_func` is still probed, proving the repair did not over-match
- [ ] T028 [P] [US3] Test in `scripts/tests/test_check_gate_liveness.py`: the skip set is printed with each entry and its reason when non-empty, and prints `0 skipped as heavy` when empty

### Implementation for User Story 3

- [ ] T029 [US3] Rewrite `is_registry_function()` in `scripts/check-gate-liveness.py` to resolve the command through the registry and test `spec.bash_func`, instead of matching command text — spec 012 FR-001, second site in this file
- [ ] T030 [US3] Emit the skip set in `scripts/check-gate-liveness.py` on every run, each entry named with its reason, and the explicit `0 skipped as heavy` form when empty (FR-008)
- [ ] T031 [US3] Bound per-probe captured output in `probe()` in `scripts/check-gate-liveness.py`, marking the capture truncated on overflow and retaining leading bytes where traceback markers appear (FR-007)
- [ ] T032 [US3] Add a test asserting the output cap exceeds the largest output any healthy gate is known to produce, so the cap can never be the reason a healthy gate is misread
- [ ] T033 [US3] Verify the skip set is unchanged after rewriting every registry command's invocation syntax — the FR-008 / spec 012 SC-001 experiment, run as a one-off and recorded in the commit message

**Checkpoint**: US3 complete — the decision of which gates are cheap is correct and visible.

---

## Phase 6: Polish & Cross-Cutting

- [ ] T034 [P] Update `docs/scripts/gate-registry.md` with the aggregate-budget behaviour and the `unreached` verdict
- [ ] T035 [P] Record the removal of `check-adr-immutable.py` and the resulting loss of automated Principle IV enforcement in a new record under `docs/decisions/`, with `supersedes` where applicable — closed ADRs themselves stay untouched per FR-012
- [ ] T036 Run `./scripts/refresh-decisions-digest.sh` after T035
- [ ] T037 Run `python3 -m unittest discover -s scripts/tests`
- [ ] T038 Run `python3 scripts/check-gate-wiring.py --can-fail` — this exercises the liveness gate's own sabotage control
- [ ] T039 Run `python3 scripts/check-gate-wiring.py --registry --registry-cross --reachability --parity`
- [ ] T040 Time `python3 scripts/check-gate-liveness.py` end-to-end and confirm it is under 60 s (SC-003)
- [ ] T041 Sample resident memory across a full run and confirm the peak is under 4 GB (SC-006)
- [ ] T042 Run the `quickstart.md` validation scenarios end to end

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup. **Blocks all stories.**
- **US1 (Phase 3)**: Depends on Phase 2. **No dependency on US2 or US3** — it is independently completable and delivers the bulk of the saving, which makes it the MVP.
- **US2 (Phase 4)**: Depends on Phase 2. Independent of US1.
- **US3 (Phase 5)**: Depends on Phase 2. Independent of US1 and US2.
- **Polish (Phase 6)**: Depends on the stories being completed.

### User Story Dependencies

- **US1 (P1)**: Standalone. Removal only.
- **US2 (P1)**: Standalone. Touches only `check-gate-liveness.py`.
- **US3 (P2)**: Standalone in scope, but **shares `scripts/check-gate-liveness.py` with US2** — the two must not be implemented in parallel by different agents without a merge step.

### Within Each User Story

- Tests MUST be written and observed to fail before implementation.
- For US1, removal order matters: registry entries and control first, then the `static-gates.sh` lines, then `git rm`. Doing the script first leaves T013 red.
- For US3, T029 must precede T031's verification, since the repaired detection changes which gates are probed and therefore what the cap must survive.

### Parallel Opportunities

- T003 and T004 are parallel — a test and a baseline run, different concerns.
- All test tasks (T016–T019, T026–T028, T032) are parallel within their story.
- T034 and T035 are parallel — different files, unrelated.
- **US1 is parallel-safe against nothing** — it edits the registry that T002/T003 load. Finish Phase 2 first.
- **US2 and US3 are NOT parallel-safe**: both edit `scripts/check-gate-liveness.py`.

---

## Parallel Example: User Story 1

```bash
# The registry edits are sequential and must precede the file deletion:
Task: "Remove the two GateSpec entries from scripts/src/sgate/commands/_registry.py"
Task: "Remove the 'adr-immutable' Control entry from scripts/src/sgate/commands/_registry.py"
# Then, in parallel (different files):
Task: "Remove the gate lines from scripts/ci/static-gates.sh"
Task: "Update docs/scripts/gate-registry.md"
```

## Parallel Example: User Story 3

```bash
# All three tests first — they fail against the current implementation:
Task: "Test: bash_func gate is counted, not probed"
Task: "Test: gate without bash_func is still probed"
Task: "Test: skip set printed, including the empty form"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup (baseline)
2. Phase 2: Foundational
3. Phase 3: US1 — removal
4. **STOP and VALIDATE**: confirm the ~120 s is gone and the cross-checks are green
5. US2 and US3 are independent increments after that

US1 alone satisfies the user's original complaint (the suite no longer overruns), so it is a complete, shippable increment. US2 and US3 are the guardrails that stop the failure class from recurring.

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → validate → **the performance problem is solved**
3. US2 → validate → the suite can no longer overrun silently
4. US3 → validate → the skip decision is correct and visible
5. Polish

Each step leaves the suite green on its own.

### Suggested Commit Boundaries

One commit per phase, each green:

1. `chore(gates): remove closed-ADRs and frontmatter-drift gates` (US1)
2. `feat(gates-are-alive): aggregate budget and unreached verdict` (US2)
3. `fix(gates-are-alive): registry-function detection keyed on resolved identity` (US3)
4. `docs: record the gate removal and refresh the digest` (Polish)

---

## Notes

- **[P]** = different files, no dependency
- **[Story]** labels map tasks to spec.md user stories for traceability
- Tests fail before implementation — for US2 and US3 they must, since the current code does the opposite of what those stories require
- **Do not** perform the R1/R2 batching work from `research.md`. The owner chose removal over optimisation; the research is retained as evidence of what the cost was
- **Do not** edit closed ADRs that reference the removed gates (FR-012)
- **Do not** touch `.github/workflows/ci.yml` or `check.sh` — both are entry points excluded from this work
- Recorded but out of scope: `GateSpec(name="decisions digest within budget")` declares `bash_func="digest_and_sizes"`, which does not exist in `static-gates.sh`. It needs its own change with its own verification
- The concurrency limit is the shared file `scripts/check-gate-liveness.py` across US2 and US3