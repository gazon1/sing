# ci-fast-only-pr-full-on-schedule

**Status:** proposed · **Issues:** — · **ADRs:** `2026-10-22-ci-fast-only-pr-full-on-schedule`

## What

The JVM tier of CI answers every pull request with the **fast** population — the classes
marked for the quick cycle — and the **full** population runs on a schedule, on a manual
request, and on demand through an opt-in label.

Today every pull request runs the full population. That is 91 slow classes out of 485, and
they dominate the wall-clock of a pull request while almost never being the reason a pull
request is red: a slow class that fails is a signal about the nightly, not about the change
under review. But running the full set on every push is also how the suite got expensive
enough that people stopped running it locally.

Three requirements move:

- **REQ-3** gains a population. A coverage floor is now a floor *over a named population*,
  and each population carries its own floor. The floors are one-sided in both directions —
  a fuller run is still allowed to pass — so the fast floor and the full floor can live
  side by side without either of them being a trap for the other.
- **REQ-23** stops being blind to slow classes. The declared-class comparison covered
  `@Tag("fast")` because a slow class was excluded from every local run by design. That
  justification dies with this change: slow classes now run on a schedule, and "it did not
  run" is a thing that must be noticed. The comparison extends to the slow population
  wherever the run's population includes it.
- **REQ-24 (new)** states the tier's contract: the JVM tier answers on every pull request,
  the population is decided in one place, and every later step reads that decision.

## Why

The split is not a speed optimisation; it is a claim about what a pull request should
answer. A pull request asks "is this change sound?". The fast population is the part of
the suite that answers that question quickly and whose failures are about the change. The
slow population answers "is the whole product still sound?", which is a nightly question,
and a nightly failure should not block a pull request that has not broken anything.

The cost of the split is that two populations now exist, and two floors have to exist with
them. That cost is real, and it is why the requirements are written the way they are:

- The floor for a population is rewritten only from a measurement **of that population**
  (REQ-15). A fast floor taken from a full run would be unreachable by construction — the
  same defect the baseline file already records for the number 1003.
- The comparison against declared classes extends to slow classes **where the run's
  population includes them**, so a fast-only run is not asked about slow classes it was
  never going to run, and a full run is not allowed to quietly drop them.

## Scope

**In scope:** the population a pull request runs; the trigger that widens it; the coverage
floors per population; the declared-class comparison for the slow population; the record of
which population a run measured.

**Out of scope, deliberately:**

- **What a slow class is.** The `fast`/`slow` split itself is unchanged, and the tag
  strategy skill still owns it. This change decides *when* each population runs, not what
  belongs to either.
- **A path filter that widens the population automatically.** A pull request that changes
  the database schema or the user interface arguably deserves the full population. That is
  a recommendation, not a contract, and it is left for a separate change: an automatic
  widening rule makes the population a function of the diff, which is a much harder thing
  to explain than a label.
- **The count floors by number of tests.** They already describe the fast population, and
  they stay as they are — a fuller run is above them by construction.
- **The instrumented-test tier.** It is a different runner on a different device and has
  its own floors.

## What this changes in the test plan

The verification posture changes, not the product: no test in this repository asserts
anything about the app's behaviour differently after this change. What changes is which
classes run when, and which floor is compared against which run.
