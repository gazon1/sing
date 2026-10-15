# Research: Bounded Gate Liveness Probing

Phase 0 output for `specs/013-gate-probe-cost`. Each decision records what was chosen, why, and what was rejected.

## R1 — Batch mechanism for per-record repository reads

**Problem**: `check-adr-immutable.py` resolves a frontmatter field by spawning `git show` once per ADR (`:167` → helper `:76`) and once per *commit* of that ADR's history (`:122-124` inside `immutable_since`). Cost is `O(records × commits)` subprocess spawns.

**Decision**: Two queries instead of thousands.
1. One `git log --format=%H --name-status -- docs/decisions/` — yields the commit set, the per-path commit lists, and rename edges, all from a single history walk.
2. One long-lived `git cat-file --batch`, fed `<rev>:<path>` lines, resolving every needed blob in a single spawn.

**Rationale**: Verified on this repository, not assumed:
- `git log --format='%H' -- docs/decisions/` → 365 commits in one spawn
- `printf '<rev>:<path>\n…' | git cat-file --batch` → resolves each reference, one spawn for all

`git` version 2.56.0. No new dependency; the idiom is standard batch reading.

**Alternatives considered**:
- `git log -p` + patch parsing — 1 spawn, but reading a frontmatter field requires reconstructing full file state from partial diffs. If the frontmatter block was never touched by a later edit, the correct value lives in an earlier blob regardless. More parsing complexity for the same order of cost.
- `git cat-file --batch-command` with `--filter` — faster on large corpora; its blob filtering differs across historical revisions, and reading a field *exactly as it was at a revision* is precisely what this gate needs. Premature.
- Cache the per-record spawns — treats the symptom, adds a staleness failure mode, and leaves first-run cost unchanged.

**Resolves spec assumption**: "The batching mechanism available in the repository tooling is adequate" — **confirmed**. This was the spec assumption most likely to fail; it does not.

## R2 — Per-file history without per-file spawns

**Problem**: `immutable_since()` uses `git log --follow` per path (`:120`). `--follow` is per-path by construction, so it resists batching directly.

**Decision**: Reconstruct the equivalent from the single commit walk in R1. Each path's commit list comes from `--name-status`; rename edges (`R100 old new`) attribute the old path's commits to the new path.

**Rationale**: Satisfies FR-004 without reintroducing per-path spawns. A decision rename is a contiguous move, not a reorganisation, so rename edges from one walk reconstruct `--follow` correctly for this corpus.

**Alternatives considered**:
- Hybrid — `--follow` only for paths with many renames — is correct but maintains two code paths that must agree, and the threshold cannot be known in advance. Rejected.

## R3 — Aggregate probe budget

**Problem**: `check-gate-liveness.py` probes serially (`:291`), each with `PROBE_TIMEOUT = 300` (`:92`), and has no aggregate bound. Worst case: 46 × 300 s ≈ 3.8 hours, unreported.

**Decision**: Add an aggregate wall-clock budget. Gates the budget expires before are reported under a new verdict, `unreached`, distinct from `slow`.

**Rationale**: FR-006 requires naming what was not reached. Folding it into `slow` would merge "ran and was slow" with "never ran" — the verdict-merging this gate's own docstring identifies as the cause of a false red CI job.

Budget value is set in implementation from the measured post-Part-1 serial sum (~31 s), with headroom. Not fixed in this document because it must be derived from a measurement that has not yet been taken.

**Alternatives considered**:
- Lower `PROBE_TIMEOUT` from 300 s — bounds nothing in aggregate, and 300 s is load-bearing for genuinely slow healthy gates on cold runners. Kept as a secondary lever, not the primary.
- **Parallel probing** — reduces wall-clock but multiplies resident memory, which is the failure recorded in spec 012 SC-004 (≈50 GB on 2026-10-15). Deferred rather than rejected: after Part 1 the serial sum is ~31 s and SC-003 is met without it.

## R4 — Bounded output capture

**Problem**: `probe()` uses `capture_output=True` (`:236`), holding a gate's entire stdout and stderr in memory. Measured: `gate scripts' own unit tests` runs 210 s and is verbose.

**Decision**: Cap captured output per probe; on overflow mark the capture truncated and retain leading bytes — traceback markers appear early, and they are what classify the verdict.

**Rationale**: The bound must hold independently of whether the skip rules happen to exclude the verbose gate today. A skip is a runtime judgement; memory must not depend on it.

**Alternatives considered**:
- Discard output, re-run on failure — doubles the cost of precisely the case that matters most.
- Redirect to a temp file — equivalent memory profile to a cap in the common case, more filesystem churn, harder to test. Rejected for simplicity.

## Measurement baseline (2026-10-15)

Per-probe timing, output discarded, 60 s ceiling per probe, 45 of 46 probes completed:

| Metric | Value |
|---|---|
| Serial sum | 151.7 s |
| `ADR frontmatter drift` | > 60 s (hit ceiling) |
| `closed ADRs keep their body` | > 60 s (hit ceiling) |
| Share of total from those two | 120.3 s = 79 % |
| Remaining 43 probes | ~31 s |
| Corpus | 601 ADRs, 365 commits touching `docs/decisions/` |
| ADRs with `status: closed` | 0 (569 accepted, 13 open, 12 deferred, 7 superseded, 1 deprecated) |

Zero closed ADRs is why most of the work is spent establishing that nothing is in scope — and it is why the cost compounds silently: the gate does `O(records × commits)` work to conclude that its input set is empty.

## R5 — Root cause of `ADR frontmatter drift`, and a second instance of spec 012's class

Investigated in Phase 1; this closes the open item above.

**Finding 1 — same defect class, same file, second site.** `is_registry_function()` (`:197-204`) decides whether a gate is a registry shell function by testing `cmd.split()[0] in functions`. Since every command became `sgate <subcommand>`, `cmd.split()[0]` is always `'sgate'` and never a function name, so **the detection can no longer fire**. The gate's own comment states these gates are "not probeable out of context — they need the harness that sourced them", yet all three are now probed:

| Gate | `bash_func` | detected |
|---|---|---|
| `decisions digest within budget` | `digest_and_sizes` | False |
| `digest has no double suffix` | `digest_no_double_suffix` | False |
| `ADR frontmatter drift` | `adr_dry_run` | False |

This is precisely the defect class spec 012 was written for, in the same file fixed on 2026-10-15. The heavyweight filter was one site; this is another. Both are text matching broken by the same refactor.

**Finding 2 — a dangling registry reference.** `GateSpec(name="decisions digest within budget")` declares `bash_func="digest_and_sizes"`, and that identifier appears **zero times** in `scripts/ci/static-gates.sh`. Both execution paths render it (`gates.py:50`, `_registry.py:698`): `bash -c '. static-gates.sh && digest_and_sizes'`. Under the registry path that is `command not found`.

**Finding 3 — divergence between two paths to the same gate.** Probing `sgate decisions_digest_within_budget` returned rc=0 in 0.401 s, because the CLI resolves the name to its own Click command. The registry's `BASH_C` path instead builds a `bash -c` snippet pointing at the missing function. The gate is reachable by two routes that do not agree; the probe exercises the healthy one and never touches the broken one. This is why the defect is invisible from a probe result.

**Finding 4 — the frontmatter gate's own cost.** `adr_dry_run` → `normalize-adr-frontmatter.sh` → `exec python3 normalize-adr-frontmatter.py`, which processes all 601 ADRs. Whether its cost is per-record spawns or in-process work was not isolated; it is a candidate for the same batching treatment as R1, and that must be established at implementation time before assuming R1 covers both.

**Decision**: `is_registry_function()` is fixed in the same change as the heavyweight filter, resolving through the registry rather than matching command text. It is not new scope — spec 012 FR-001 already requires exactly this, and fixing one site while leaving the other would mean the same file still contains the class the spec was written to prevent.

**On the dangling `bash_func`**: recorded, not fixed here. It is a registry-harness defect, not a probe-cost defect, and fixing it changes which gates run — a different change with its own verification.