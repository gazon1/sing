# The replay — what a split would have cost on six months of real history

Question: if `:shared` had been split this way, what would the median commit have cost? `replay.py` maps every commit in
the window (2026-05-05 → 2026-11-05) to the clusters it touches, maps clusters to hypothetical modules, and prices each
commit as the sum of its modules' `T = a + b·N` from `cost-model.md`. Co-changed clusters pay for both modules — which is
the whole point.

Three inputs, each with a caveat that is part of the answer:

- **Paths.** 1,428 commits; 297 renames resolved to a fixpoint, 798 commits have every path resolvable, 561 do not and are
  excluded. Commits touching no JVM-compiled file (tests, `androidMain` only) are counted apart, not billed as free.
- **ABI.** A signature change rebuilds every dependent module; a body-only edit does not (measured, `cost-model.md` Gate E).
  Each commit is classified from its diff: a non-private declaration at ≤4 spaces indentation. **42 % of usable commits are
  classified ABI** — a regex, not a parser, so the answer is reported under three brackets.
- **Model.** The measured curve is 2.3× cheaper than `:shared` (parity, `cost-model.md`), so scenarios are shown raw and
  calibrated; a uniform scale changes no ratio.

## Scenarios

`S0` one module (the control), `S1` features + core layers (38 modules), `S2` every cluster its own module (74 modules).
Calibrated model, seconds, over 798 usable commits:

| scenario | bracket | median | p80 | mean | p80 vs S0 |
|---|---|---|---|---|---|
| S0 | any | 60.1 | 60.1 | 60.1 | — |
| S1 | body-only | 5.8 | 10.5 | 7.2 | −83 % |
| S1 | all-ABI | 60.1 | 76.8 | 52.5 | **+28 %** |
| S1 | classified (42 %) | 8.7 | 65.1 | 27.4 | **+8 %** |
| S2 | body-only | 4.7 | 9.5 | 6.3 | −84 % |
| S2 | all-ABI | 35.6 | 71.1 | 40.8 | +18 % |
| S2 | classified | 7.8 | 45.6 | 22.1 | −24 % |

The shape is the finding:

- **The median commit wins large** — −85 % and more — because most edits become small, and a small module recompiles fast.
- **The p80 commit loses or barely wins.** An ABI edit in a module other modules depend on now rebuilds those modules too, and
  after a split there are more modules to rebuild. At 42 % ABI the 80th percentile is *worse* than today for S1 (+8 %) and
  only −24 % for S2.
- **The whole answer flips between brackets**: −83 % (if no commit changes an ABI) to +28 % (if every one does). A median
  that improves while the tail worsens is a change in variance, not a speed-up, and it is the tail that people notice.

## Gate C

The rule was: if the optimistic cell (parallel aggregation, body-only bracket) still misses −30 %, stop; if the pessimistic
cell (serial, all-ABI) also clears −30 %, go; otherwise the decision moves to the layer plan with a range.

| cell | value |
|---|---|
| optimistic — parallel, body-only, S2 | −84 % → the stop test is passed |
| pessimistic — serial, all-ABI, p80, S1 | +28 % → the go test is **failed** |

And Gate E removes the optimistic cell anyway: two dependent modules **do not compile in parallel** (measured wall 6.0 s vs
makespan 2.76 s), and each compile task costs ~40 % more than in a single-module build. Correcting the serial numbers by that
40 % makes S1's classified p80 ≈ 91 s, i.e. **+51 %**.

**Verdict: no clean go.** On this evidence a split improves the typical edit and degrades the expensive one. That is a trade
someone may still want — but it is not the −30 % the plan asked for, and the model cannot settle it either way.

## What would settle it

Not another model. **One real two-module split, measured end to end** — and `core/ui` is five import lines away from being
that split (`cluster-map.md`). A real split would replace both the stand and this replay with two numbers that cannot be
argued with: the task time of a 108-file module and the task time of what is left.

## What this does not establish

- The ABI classifier is a regex: 42 % is plausible for an actively developed app but unverified commit by commit; the
  brackets exist because of that, and the verdict depends on the bracket.
- `S1`'s module assignment is one plausible partition, not the only one. A different grouping (for example keeping the tags
  and projects features together with tasks, which co-change heavily) would change the numbers.
- Android is not modelled at all: a commit touching only `androidMain` costs 0 here and something real in the Android
  compilation (H8, unmeasured).
