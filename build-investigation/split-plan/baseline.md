# Phase 0 — baseline of `:shared` and the H0 verdict

Date: 2026-11-05. Commit `2b4cf8318` on `perf/split-plan` (branched from `origin/main` `3d572aec8`).
Every number below is copied from `results/SUMMARY.tsv` or `results/p0-in-process-N/result.txt`;
nothing was read off a terminal.

## Environment

| Item | Value |
|---|---|
| Kotlin | 2.4.10 |
| Gradle | 9.8.2 |
| Compiler strategy | `in-process` (`gradle.properties:90`) |
| Gradle JVM args | `-Xmx6144M`, `workers.max=2`, `parallel=true`, configuration cache on |
| CPUs | AMD Ryzen 7 5700G, 16 cores |
| IDE / other builds | closed; one measurement batch at a time |

Recorded in `results/env.txt`.

## Method

`cycle.sh shared patches/task-repository-body-only.patch results/p0-in-process-N`, six times:
one warm-up (`-1`, the first compile in a checkout that had never been built) and five counted
(`-2` … `-6`). Each cycle forced a real compile with `--rerun-tasks`, required the settle run to be
`UP-TO-DATE`, applied the patch, measured, and reverted it — the tree was verified clean after every
cycle (exit code 3/5 would have stopped the batch; the batch exited 0).

The metric is the task's own time from the Kotlin Build Report. `wall_ms` is recorded beside it as an
upper bound: `./gw` queues in the machine-wide slot pool before Gradle starts.

## Result

| cycle | task_s | wall_ms | exit | verdict | load1 (per core) |
|---|---|---|---|---|---|
| p0-in-process-1 *(warm-up)* | 56.910 | 57,885 | 0 | non-incremental, KT-62686 | 1.62 (0.10) |
| p0-in-process-2 | 60.055 | 61,383 | 0 | non-incremental, KT-62686 | 9.00 (0.56) |
| p0-in-process-3 | 56.840 | 58,061 | 0 | non-incremental, KT-62686 | 9.48 (0.59) |
| p0-in-process-4 | 62.403 | 63,685 | 0 | non-incremental, KT-62686 | 8.87 (0.55) |
| p0-in-process-5 | 62.161 | 63,260 | 0 | non-incremental, KT-62686 | 9.84 (0.62) |
| p0-in-process-6 | 58.798 | 60,034 | 0 | non-incremental, KT-62686 | 9.08 (0.57) |

Over the five counted cycles:

```
n=5 min=56.840 max=62.403 median=60.055
```

Every cycle, without exception:

- `Classpath changes info passed from Gradle task: NoChanges`
- `Non-incremental compilation will be performed: Incremental compilation might be incorrect (KT-62686)`
- `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)`
- 1,244 files in the compile iteration — the whole module, not the edited file
  (counted from `results/p0-in-process-N/report.log` with
  `awk '/^  Compile iteration:$/{f=1;next} f&&/^    shared\//{n++;next} f&&n>0{exit} END{print n+0}'`)

## H0 — confirmed

No counted cycle was incremental, and the median task time (60.055 s) is inside the pre-registered
50–65 s window. The fallback reproduces on this machine and this commit exactly as the ADR recorded it
at `a4fbed7a0` (62.0 s), so the rest of the plan may proceed and this median is the denominator for
Phases 2–4.

The thresholds were fixed before the numbers were seen and were not adjusted afterwards.

## Spread against host load

All counted cycles ran between 0.55 and 0.62 load per core — above the idle warm-up (0.10) and below
the 1.0/core gate. The spread is 5.56 s (9.8 % of the median) and does **not** track load: the busiest
cycle (0.62/core) took 62.161 s while 0.59/core took 56.840 s. So the 60 s median is a property of the
commit and the module, not of the host.

Consequence for later phases: a −30 % threshold (≈ 42 s) sits far outside this 5.6 s spread, so five
cycles per configuration are enough for the plan's `3.4` significance rule. The warm-up cycle is kept
in `SUMMARY.tsv` and excluded from the median by name.

## Two notes on the instrument

- The extraction line for the source count was speculative and never matched (the report lists the
  files rather than a count), so the count in the table above is derived from the saved report with the
  command shown. The script line was removed after this batch; it had no effect on any recorded value.
- `host.txt` records `before` and `after` samples. Only `before` is used as evidence: the `after`
  sample measures this compile.
