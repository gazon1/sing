# ci-checks-parallel-split

Issue: #100 · Backlog entry: `ci-parallel-split-blocked-by-new-intra-job-coupling`
ADR: `2026-10-05-ci-checks-run-in-parallel` (superseded) ·
`2026-10-06-ci-single-gate-registry-and-leaf-split` (`status: accepted`)

**Status: implemented as a four-leaf shape, 2026-10-06.** `ci.yml` now runs
`static`, `tests`, `android` (two-leg matrix) and the `ci-gate` aggregator. The
tasks below were written for six leaves; what landed keeps `tests` whole, because
the three couplings this change exists to respect are all inside it. The design
options in "Try next" remain available for splitting `tests` itself, which is
optional rather than corrective.

## What

Re-introduce parallel execution in `test-and-check` — 24.25m to 9.2m, measured —
by designing around the three couplings `main` added, rather than by re-applying
a shape that predates them.

## Why

The six-leaf split was built, measured and green (run 37212487694: 10 jobs, 1585
tests, 2.65x). It was withdrawn because `main` had meanwhile coupled the job
internally in three places the split assumed absent:

1. `Stamp run start` writes `$RUN_STARTED`; `Check executed test counts` and
   `Check coverage floors` both read it. A floor is only meaningful against the
   results written by the same run.
2. `Flake analysis vs previous run` reads this run's
   `shared/build/test-results/jvmTest` against the previous run's `junit-results`
   artifact. Two runs, two jobs, one comparison.
3. Kover moved into a job that also runs the test tasks, because a coverage
   report has to come from a test run rather than from whatever a cache held.

Splitting on "these steps do not import each other" was true of the old job and
false of the new one. The old shape would have produced a green run whose count
floors and flake analysis compare across a boundary they were never written to
cross.

That is the specific reason this change is deferred rather than re-applied. A
faster pipeline that quietly checks less is worse than the 24 minutes it saves,
and it is worse in the way that is hardest to notice: the run is green, the
artifact is there, and nobody looks at the number.

## The three designs

Each is a real answer, not a variation on the same one.

1. **Publish `RUN_STARTED` as a job output** and pass it to the floor-checking
   leaves. Smallest change; keeps every floor comparing against a stamped run.
   Cost: a floor leaf that runs before its result leaf still has nothing to
   check, so the leaf boundary has to be drawn around the floors rather than
   around the test tasks.
2. **Move each floor into the leaf that produced the results.** `Check executed
   test counts` goes with `:shared:jvmTest`, `Check coverage floors` goes with
   the kover job, and the aggregator keeps only the assertion. This is the
   cleanest mapping and the one the coupling was already pointing at. Cost: two
   floors in two jobs means two places to update when a floor moves.
3. **Replace the two-run flake comparison with a stored baseline.** Removes the
   cross-job edge entirely. Cost: a genuine flake — passed here, failed there —
   is no longer visible as such, which is the thing that comparison exists to
   find. This should not be adopted alone.

Recommendation: 2, with 1 as the mechanism for anything the aggregator still
needs to stamp. Reject 3 unless the flake step is otherwise being removed.

## What not to do

Do not re-apply the six-leaf split as written and accept the reduced coverage
as a known cost. A gate that cannot fail is worse than no gate, and this
particular one would fail *green*: every check would still run, and two of them
would be measuring something other than what their names say.
