# Quickstart: Verifying the Liveness Fix

How to reproduce the baseline, implement against it, and prove each success criterion. Every command runs from the repository root. No JVM required.

## Prerequisites

- The `sgate` CLI installed and on PATH: `uv pip install --system --editable scripts/src/`
- A clean working tree, except where a step says otherwise
- `git` history present (all measurements below depend on it)

## 1. Reproduce the baseline

Confirm the two gates that dominate the cost:

```bash
/usr/bin/time -f '%e s' timeout 60 python3 scripts/check-adr-immutable.py
/usr/bin/time -f '%e s' timeout 60 ./scripts/normalize-adr-frontmatter.sh
```

Expected **before** the fix: both exceed 60 s and are killed (`rc=124`). Exit code 124 is the signal, not the timeout itself.

Confirm the full serial cost of probing, output discarded so nothing accumulates in memory:

```bash
time python3 scripts/check-gate-liveness.py
```

Expected **before**: over 280 s. Expected **after**: under 60 s.

Which gates are probeable, and which are skipped:

```bash
python3 scripts/check-gate-liveness.py --list
```

## 2. Confirm the process-spawn claim (FR-001, SC-005)

The cost is one spawn per record per commit, not per record:

```bash
git log --format='%H' -- docs/decisions/ | wc -l   # commits touching the corpus
grep -c '^status:' docs/decisions/*.md | wc -l     # records
```

Batching target: a constant number of `git` spawns — one `git log`, one `git cat-file --batch` — regardless of these two numbers.

To assert the invariant directly, in a temporary git repository: create 100 ADR files with 10 commits each, run the gate, and count spawns. The count must not grow by 1000.

## 3. Confirm no verdict regression (SC-008)

The optimisation changes how facts are read, never what is concluded. Seed a known violation and confirm it is still caught:

```bash
# In a scratch worktree: take a closed decision and edit its body.
python3 scripts/check-adr-immutable.py    # MUST still report the rewrite
```

Also confirm the working-tree comparison still fires — this is FR-003 and the gate's stated reason for existing:

```bash
# Edit a closed decision's body but do NOT commit.
python3 scripts/check-adr-immutable.py    # MUST still report the uncommitted edit
```

The second check is the one a history-only optimisation would silently drop. It is not optional.

## 4. Confirm the budget behaves (FR-005, FR-006, SC-004, SC-007)

Register a gate that never terminates. Then:

```bash
time python3 scripts/check-gate-liveness.py
```

Expected: completes within the aggregate budget plus one per-probe budget, and the output names each unreached gate with `Not checked, not passed.` A count without names fails SC-007.

Repeat with several hanging gates. Runtime MUST NOT scale with the number of hangs.

## 5. Confirm the skip set is reported (FR-008, SC-009)

```bash
python3 scripts/check-gate-liveness.py --list | tail -1
```

Both a non-empty skip set (each entry named with its reason) and the explicit `0 skipped as heavy` case must be present. The empty case is the one that catches a silently-dead filter — the exact failure of 2026-10-15.

## 6. Confirm memory stays bounded (FR-007, SC-006)

```bash
( while true; do free -m | awk 'NR==2{print $3}'; sleep 2; done > /tmp/mem.log ) &
MON=$!
python3 scripts/check-gate-liveness.py
kill $MON
sort -n /tmp/mem.log | tail -1      # peak MB attributable to the run
```

Expected: under 4000 MB. A probe whose output capture is unbounded shows here first.

## 7. Full regression

```bash
python3 -m unittest discover -s scripts/tests
python3 scripts/check-gate-wiring.py --can-fail   # exercises the liveness gate's own sabotage control
```

## Expected results

| Step | Before | After |
|---|---|---|
| 1 — immutability gate | > 60 s, `rc=124` | < 5 s |
| 1 — frontmatter gate | > 60 s, `rc=124` | < 5 s |
| 1 — full liveness run | > 280 s | < 60 s |
| 2 — spawn count | grows with records × commits | constant |
| 3 — seeded violations | caught | caught |
| 4 — hanging gates | 300 s each, unreported | within budget, each named |
| 6 — peak memory | unbounded output capture | < 4 GB |

## If a criterion fails

- **Gates still slow** — check spawn count first (step 2). A constant spawn count with a long runtime means the cost is in-process, not in spawning, and the batching decision in `research.md` R1 needs revisiting rather than extending.
- **Verdict regression** — stop. SC-008 failing means a property of Principle IV was traded for speed; revert rather than narrow the test.
- **`unreached` non-empty on a normal tree** — the aggregate budget is below the serial sum. Raise it; do not lower `output_cap` to compensate.