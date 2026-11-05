# Split plan — Phase 0 stand and Phase 1 hot map

Answers the next step after https://github.com/gazon1/sing/issues/2600. That issue's result is recorded in
`../kt-62686/README.md` (ADR `2026-11-05-kt-62686-is-kmp-jvm-compilation`): a KMP module's JVM compilation bails
to a full recompile on a body-only edit, with or without any plugin, under either execution strategy.

**This work does not try to remove that fallback.** It measures the *price* of it, so that a module split can be
judged by the only thing a split can actually buy: a shorter `compileKotlinJvm` on a hot file. Nothing here
contradicts the ADR's decision, which was that a split is not justified *as a cure for the fallback*.

## One cycle

```bash
build-investigation/split-plan/cycle.sh shared \
  build-investigation/split-plan/patches/task-repository-body-only.patch \
  build-investigation/split-plan/results/p0-in-process-1 [in-process]
```

`cycle.sh <module> <patch> <out-dir> [strategy]` does, in order:

1. refuses to run when the host is loaded above `1.0` per core (`SPLIT_PLAN_MAX_LOAD_PER_CORE` overrides) or
   when the tree is not clean outside `results/`;
2. `--rerun-tasks --no-build-cache` compile — a *real* compile, which creates the incremental state;
3. the same compile without `--rerun-tasks` must report `UP-TO-DATE` — the settle check. `--no-build-cache`
   alone does not force a compile, so without this step a "settle" can be `UP-TO-DATE` while the compiler never
   ran (the trap recorded in the ADR);
4. samples the host (`/proc/loadavg`, `/proc/pressure/cpu`, daemon counts) — **before** the measurement,
   because a sample after it measures this compile;
5. applies the patch, marks the report directory, runs the measured compile with `--info`;
6. reverts the patch (also on failure, via `trap`) and verifies the tree is clean again.

The verdict and the task time are read from the **Kotlin Build Report** (`build/reports/kotlin-build/`), not
from wall clock: `./gw` waits in the machine-wide slot pool before Gradle starts, so wall time is an upper bound.
`task_s` is the measurement; `wall_ms` is recorded beside it.

Exit codes: `2` settle not `UP-TO-DATE` · `3` tree dirty · `4` no usable report · `5` revert failed ·
`6` report path wrong · `7` run directory already used · `8` host too busy.

## Files

| Path | What |
|---|---|
| `cycle.sh` | the cycle above; the only instrument |
| `patches/task-repository-body-only.patch` | one body-only edit in `feature/tasks/data/TaskRepositoryImpl.kt` — no signature, visibility or call-site change |
| `results/SUMMARY.tsv` | one row per cycle, written by `cycle.sh`: `run strategy commit task_s wall_ms gradle_exit non_incremental load1` |
| `results/p0-in-process-N/result.txt`, `host.txt` | committed evidence: extracted report lines, task outcomes, host state |
| `results/p0-in-process-N/*.log` | raw `--info` logs and the build report copy — local only, `*.log` is git-ignored |
| `baseline.md` | Phase 0 table and the H0 verdict |
| `analyse.py` | Phase 1 analysis, stdlib only; `python3 analyse.py --since 2026-05-05 --out .` regenerates the seven files below in about a second |
| `hot-files.txt` `hot-packages.txt` `co-changes.txt` `package-graph.txt` `cluster-data.tsv` | Phase 1 outputs (files, packages, co-changes, import graph, cluster table) |
| `cut-plan.txt` | what it costs to lift a cluster, and the greedy feedback arc set for the whole graph |
| `commit-profile.txt` | what share of the history a split could shorten, and the renames that truncate it |
| `cluster-map.md` | Phase 1 reading: what is hot, what is liftable, and what blocks it |
| `kotlin-versions.md` | H9: the fallback on 2.4.10 / 2.4.20 / 2.4.21 / 2.5.0-Beta2, and KT-82893's state |
| `cost-model/`, `cost-model.md` | the synthetic stand, the fitted `T = a + b·N`, and Gate E |
| `replay.py`, `replay.md` | six months of history replayed against three hypothetical splits |
| `results/env.txt` | toolchain and configuration the numbers belong to |

The patched **source** is never committed: it exists only between `git apply` and `git apply -R` inside one cycle.
The `.patch` file is committed, because the stand is not reproducible without it.

## Phase 1

```bash
python3 build-investigation/split-plan/analyse.py --since 2026-05-05 --out build-investigation/split-plan
```

A pinned date, not `6 months ago`: the window has to reproduce. Three questions, one pass over the tree and one
over `git log`: what changes most often (files, packages, clusters), what changes *together* (the coupling a split
must not cut), and what imports what (packages and clusters, with the shortest cycle through each candidate).

Production source sets are counted apart from test ones, packages that only test sources declare are not graph
nodes (their code lives in another module), and an import is only resolved to a package when the remainder looks
like a type or a member — otherwise `core.format.formatPadded` would fall back to the root package and invent an
edge through it. The script exits non-zero if any of those invariants breaks.

## Numbers

Every number quoted in `baseline.md` and `cluster-map.md` is copied out of `SUMMARY.tsv` or a generated analysis
file, never retyped from a terminal — the discipline of `scripts/check-published-measurements.py`, applied by hand
because that gate covers coverage numbers only.
