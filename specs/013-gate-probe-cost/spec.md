# Feature Specification: Bounded Gate Liveness Probing

**Feature Branch**: `gazon1/noddy`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "Приступай к планированию оптимизации: gates are alive всё ещё медленный — он серенно пробирует 46 гейтов и не уложился в мои 280 с"

## Measurement this spec is based on

Assumed cause: 46 serial probes. **Measurement says otherwise.** Each probeable gate was timed individually on 2026-10-15 (output discarded, 60 s ceiling per probe):

- Serial sum over 45 completed probes: **151.7 s**
- Two gates — `ADR frontmatter drift` and `closed ADRs keep their body` — consumed **120.3 s of that 151.7 s (79 %)**, both exceeding the 60 s ceiling
- The remaining 43 probes total **~31 s**; the bulk are under 500 ms
- Worst-case exposure today: 46 probes × 300 s per-probe budget, with **no aggregate budget** — 3.8 hours

So the serial loop is not the bottleneck. Two ADR gates are, and they are not slow — they hang.

### Root cause

`scripts/check-adr-immutable.py` reads a frontmatter field by shelling out to git, **per ADR, per commit**:

- `main()` iterates all 601 ADRs (`check-adr-immutable.py:165`)
- `status_at('HEAD', rel)` → one `git show` subprocess per ADR (`:167`, helper at `:76`)
- `immutable_since(rel)` → `git log --follow` per ADR (`:120`), then for **each commit in that file's history** another `git show` via `status_at` (`:122-124`)

The corpus is 601 ADRs of which **0 are `status: closed`** (569 accepted, 13 open, 12 deferred, 7 superseded, 1 deprecated), so most work is spent discovering that nothing is in scope.

`ADR frontmatter drift` is the same shape.

### Why this is expensive in a way that compounds

The cost is `O(ADRs × commits-per-ADR)` **subprocess spawns**, not O(ADRs). Every extra ADR commit multiplies the spawn count. The gate was correct when written; it degrades silently as the corpus grows, and the only symptom is a timeout reported by a *different* gate — the one probing it. That indirection is why this surfaced as a general "the liveness gate is slow" report rather than "the ADR gate does too much work".

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The two ADR gates are gone, and the registry stays consistent (Priority: P1)

A developer running the static gates no longer pays for `closed ADRs keep their body` or `ADR frontmatter drift`, and the removal leaves no dangling entry behind: no registry row naming a deleted script, no sabotage control proving a gate that no longer exists, no live document pointing at a missing file.

**Why this priority**: This is 79 % of the measured cost, and removing the gate is cheaper than optimising it. It is also the only part that can leave the suite red if done incompletely — a half-removed gate is worse than either state.

**Independent Test**: Confirm the two gates are absent from the registry and from the static gate list, the script is gone from the working tree, and Parts F, J and H all still pass.

**Acceptance Scenarios**:

1. **Given** the full corpus of 601 ADRs, **When** the static suite runs, **Then** the two ADR gates do not appear in its output and the suite's wall-clock drops by the measured ~120 s.
2. **Given** a registry cross-check, **When** it runs after the removal, **Then** it reports no control for a deleted gate and no gate entry for a deleted script.
3. **Given** a closed ADR that mentions the removed gate, **When** the ADR corpus is validated, **Then** the ADR is unchanged and still valid — historical references stay accurate as history.

---

### User Story 2 - The liveness gate has a wall-clock ceiling it keeps (Priority: P1)

However slow an individual gate is, the liveness check finishes within a stated budget and says which gates it did not reach, rather than running until each per-probe timeout expires in turn.

**Why this priority**: The current worst case is 3.8 hours and nothing reports it. A gate that cannot bound itself is the same failure this whole subsystem exists to catch, one level up.

**Independent Test**: Introduce a gate that hangs, and confirm the liveness run still completes within its budget and names the unreached gates.

**Acceptance Scenarios**:

1. **Given** a gate that never terminates, **When** the liveness gate runs, **Then** it completes within its aggregate budget and reports the hang.
2. **Given** several hanging gates, **When** the liveness gate runs, **Then** total runtime does not scale with the number of hangs, and each hung gate is named individually.

---

### User Story 3 - A gate that is cheap stays provably cheap (Priority: P2)

The measurement used to decide which gates are expensive stays correct as the tree changes, and the liveness run reports its skip set so a reader can see what was not checked.

**Why this priority**: Follows directly from the incident that preceded this work — a skip rule that stopped matching silently. The fix must not reintroduce that class while optimising.

**Independent Test**: Change the invocation syntax of every gate and confirm the skip set and the reported counts are unchanged.

**Acceptance Scenarios**:

1. **Given** a run that skips gates as expensive, **When** it completes, **Then** each skipped gate is named with its reason, including when the set is empty.
2. **Given** every gate's invocation syntax changed, **When** the liveness gate runs, **Then** the skip set is unchanged.

---

### Edge Cases

- **A dirty working tree.** The immutability gate deliberately compares the working tree against HEAD, because a history-only rule is blind to an uncommitted edit — the exact edit a pre-commit hook should catch. Any optimisation that reads status from the working tree instead of HEAD must preserve that comparison, and must not use it as a substitute where HEAD is the correct source.
- **`--follow` and renames.** The per-file history walk follows renames. Batching must preserve that, or an ADR renamed mid-life is judged by a truncated history.
- **A gate that hangs while holding a file lock.** A killed probe must not leave a lock that makes the next run fail differently.
- **The gate probing itself.** Already excluded by marker, but a batched implementation must not reintroduce the exclusion by text match.
- **Output volume under a parallel probe.** Capturing full output from several probes at once multiplies resident memory — the failure recorded in spec 012 SC-004.
- **Zero closed ADRs.** The corpus currently has none. An optimisation that assumes at least one is in scope must still terminate and report correctly when none are.

## Requirements *(mandatory)*

### Functional Requirements

> **Scope change, 2026-10-15.** The owner elected removal over optimisation: `closed ADRs keep their body`, `ADR frontmatter drift`, and `scripts/check-adr-immutable.py` are deleted rather than made fast. This removes ~120 s of the measured 151.7 s outright — it deletes the cost instead of shrinking it. FR-001, FR-002, FR-004 and FR-010 were written for the optimisation and are satisfied vacuously; the work they describe is not performed. FR-003 is withdrawn with the gate it protected. FR-011 and FR-012 are added to govern the removal itself.

- **FR-001**: Reading a repository fact for many records MUST NOT require one spawned process per record. Where the same query is issued for every record, it MUST be issued once. *(Vacuous — no such reader remains.)*
- **FR-002**: A query issued once for many records MUST return every record's answer, not a bounded subset. A batched read that silently truncates is worse than a slow one, because it reports a clean result derived from partial data. *(Vacuous.)*
- **FR-003**: ~~The immutability gate MUST preserve its working-tree-versus-HEAD comparison.~~ **Withdrawn** — withdrawn along with the gate it protected.
- **FR-004**: Per-file history resolution MUST continue to follow renames, or the gate MUST report that it does not. *(Vacuous.)*
- **FR-005**: The liveness gate MUST have an aggregate wall-clock budget, separate from the per-probe budget.
- **FR-006**: When the aggregate budget is exhausted, the liveness gate MUST name every gate it did not reach. An unreached gate reported as nothing is the failure this subsystem exists to prevent.
- **FR-007**: Per-probe output capture MUST be bounded, so that several probes running at once cannot exhaust resident memory.
- **FR-008**: The liveness gate MUST report the set of skipped gates and their reasons on every run, including when that set is empty.
- **FR-009**: A killed probe MUST NOT leave state that makes a subsequent run fail differently from an uninterrupted one.
- **FR-010**: Cost MUST NOT scale with the number of commits in an ADR's history. *(Vacuous.)*
- **FR-011**: Removing a gate MUST remove its registry entry, its sabotage control, and every live reference to it in the same change. A control that proves a deleted gate, or a registry entry naming a deleted script, is exactly what Parts F and J cross-check, and leaves both red.
- **FR-012**: Closed ADRs referencing a removed gate MUST NOT be edited. The removal is recorded where it is visible; the historical references remain accurate as history.

### Key Entities

- **Probe**: One attempt to run one gate and classify the outcome. Bounded by a per-probe budget and by a shared aggregate budget.
- **Unreached gate**: A probeable gate that the aggregate budget prevented from being probed. Distinct from a gate that passed, failed, was slow, or was skipped.
- **Batch query**: A single repository query issued once and resolved against many records, replacing one spawn per record.
- **Skip set**: The gates excluded from probing as expensive, reported in full on every run.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The immutability gate completes in under 5 seconds on the full 601-record corpus, against a measured baseline of over 60.
- **SC-002**: The frontmatter drift gate completes in under 5 seconds on the full corpus.
- **SC-003**: The liveness gate completes within 60 seconds on the current tree, against a measured baseline above 280.
- **SC-004**: With N gates configured to hang, the liveness gate's runtime does not exceed its aggregate budget by more than the per-probe budget.
- **SC-005**: Adding 100 ADRs each with 10 commits does not increase the immutability gate's process-spawn count by more than 100.
- **SC-006**: Resident memory attributable to a liveness run stays under 4 GB, preserving the bound set in spec 012 SC-004.
- **SC-007**: Every liveness run names every unreached gate; zero unreached gates are silent across all runs.
- **SC-008**: Zero regression in detected defects: the gates still report the same classes of finding on a corpus seeded with known violations.

## Assumptions

- Optimisation targets the two ADR gates and the liveness probe policy. It does not change which gates exist, nor the verdict vocabulary the liveness gate reports.
- Existing entry points in CI and in the local check script are not modified.
- The corpus's current state — 601 ADRs, none `closed` — is representative for sizing, and is assumed to keep growing. Runtime is therefore specified as a function of corpus size rather than as a fixed constant alone.
- The batching mechanism available in the repository tooling is adequate to answer "the status of every ADR at every commit" in a bounded number of spawns. Verified during planning; if it is not, this assumption is the one that fails first and the work reverts to a different mechanism rather than to per-record spawns.
- spec 012 governs the skip rules that decide which gates are considered expensive. This spec must not weaken those rules; FR-008 makes the skip set reportable so that a future regression is visible.