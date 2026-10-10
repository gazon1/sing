---
title: "Ci Parallel Split Blocked By New Intra Job Coupling"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "ci-checks-parallel-split"]
---

**Found in:** rebase of `fix/doc-governance-and-detekt-audit` onto `main`, 2026-10-05.
Not a pre-existing defect — an interaction between two changes that were each correct
on their own.

**Tracked as:** #100
**OpenSpec change:** `openspec/changes/ci-checks-parallel-split/`

**Status: CLOSED 2026-10-06.** The coupling was designed around rather than waited
out. `ci.yml` now runs four jobs — `static`, `tests`, `android`, `ci-gate` — and
`tests` deliberately stays a single job holding the run stamp, both count/coverage
floors, the kover report and the flake comparison, so none of the three couplings
this entry describes can be broken by the split. See
`2026-10-06-ci-single-gate-registry-and-leaf-split.md`.

**What is still open** is the narrower question this entry's "try next" list asked:
whether `tests` itself can be split further. That is not needed for correctness and
is not tracked as a defect. See the `tests-job-still-a-monolith` entry.

**What happened:** this branch split the 25-step `test-and-check` into six parallel
leaves and measured 24.25m -> 9.2m. `main` had meanwhile added three couplings inside
that job — the `$RUN_STARTED` freshness stamp feeding two count/coverage floors, a
flake comparison that reads this run's `shared/build/test-results/jvmTest` against the
previous run's `junit-results` artifact, and a kover job that must generate its report
from a test run rather than from a cache. The six-leaf shape was valid against the old
job and produces a *wrong* result against the new one: the floors and the flake
analysis would compare across a boundary they were never written to cross.

**Checks already performed:** confirmed all three couplings exist in
`origin/main:.github/workflows/ci.yml` by reading the step bodies, not by inference.
Confirmed the pre-rebase branch's own split was green (run `37212487694`, 10 jobs,
1585 tests, 4 artifacts) — so the failure is not "parallelism is broken", it is "that
specific shape no longer fits".

**Try next, in this order** — each is a real design, not a variation:
1. Publish `RUN_STARTED` as a job output and pass it to the floor-checking leaves, so
   the floors still compare against the run that produced the results.
2. Move `Check executed test counts` and `Check coverage floors` *into* the leaf that
   produced the results, and keep only the assertion in the aggregator.
3. Replace the two-run flake comparison with a stored-baseline one, which has no
   cross-job edge to break.

**Not to do:** re-apply the six-leaf split as written and declare the reduced coverage
an acceptable cost. A faster pipeline that checks less is the exact failure this
backlog exists to prevent, and it would be invisible — a green run is a green run.

**Lever already identified, independent of the split:** `assembleDebug` is ~10.3m of
the original 24.25m and is the tail of the critical path. A cold no-cache profile puts
`:shared:compileAndroidMain` at 28.8s, `:shared:kspAndroidMain` at 27.2s, and
`DexingNoClasspathTransform` on `:shared` plus `:androidApp:mergeExtDexDebug` at 38.2s
together — about a quarter of the build. Trimming the step to `compileDebugKotlin`
would buy most of that back and is **an owner's call, not a cleanup**: the step would
then prove the code compiles, not that the APK packages, and the workflow that installs
and runs the APK is scheduled rather than per-PR.

---
