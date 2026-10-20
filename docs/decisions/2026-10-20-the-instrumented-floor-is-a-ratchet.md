---
title: "The instrumented floor is a ratchet, and a rise is a finding too"
date: 2026-10-20
status: accepted
tags: [testing, ci, android]
---

## Context

The instrumented tier has a floor: `config/docs/instrumented-test-floor.txt`
records what an emulator run must deliver, and `scripts/check-instrumented-test-floor.py`
fails the run when the report says less.

The floor said **five classes and nineteen tests**. The tier delivered **six
classes and twenty-one tests**. The gate compared 21 against 19, found no drop,
and stayed green.

That is not a near miss. The floor is the only record of how big the tier is,
and it was wrong by two tests and a whole class — the kind of drift that nobody
notices, because the direction the gate watches is the direction that hurts. A
suite that shrinks is a loss of coverage; a floor that understates the suite is
a record that describes a tier that no longer exists.

The JVM floor (`scripts/check-test-runs.py`) has the opposite shape, and the
difference is not an oversight:

- The JVM tier runs under several legitimate configurations. `-Ptest.tags=fast`
  selects a subset, `-Ptest.tags=fast,slow` selects all of it, and a filtered
  `--tests` run selects less. A run that delivers *more* than the JVM floor is
  therefore normal, and the floor is raised in the same commit that adds tests.
- The instrumented tier has **one** configuration. There are no tags and no
  filters — `:androidApp:connectedDebugAndroidTest` runs the whole
  `androidTest` source set, or it does not run. So a run that delivers more
  than the recorded floor cannot be a fuller configuration. It can only mean the
  floor is behind.

## Idea

- **A.** Keep the one-sided floor. A run that delivers less is a finding; a run
  that delivers more is fine.
- **B.** Ratchet in both directions: a run that delivers *more* than the floor
  is a finding too, and the floor is raised deliberately rather than by the
  gate.
- **C.** Drop the floor and publish the count in the run summary instead. A
  number nobody checks is a number nobody reads.

## Decision

**B.** The instrumented floor ratchets in both directions.

- A run that delivers **more** than the floor is a finding: "the floor is behind
  the suite". The gate names the direction and the numbers.
- Raising the floor is **deliberate**: `scripts/check-instrumented-test-floor.py
  --update-floor` rewrites the measured counts into the generated block of the
  floor file. It refuses a drop unless `--allow-drop` is given, because a drop
  means the tier lost coverage and that is a thing to investigate, not to
  regenerate. It refuses a run with skipped tests. It warns when the floor
  rises, so a filtered run is not recorded as the floor. It leaves the prose
  above the markers untouched.
- The `skipped` ceiling stays a **policy value of zero**, never a measurement.
  A writer that measured it would raise the ceiling every time a run skipped
  something — which is the one thing the ceiling exists to prevent.
- The **JVM floor keeps its one-sided semantics.** A rise there is a fuller run,
  and the spec says so (`openspec/specs/test-execution-integrity/spec.md`,
  REQ-1). The two tiers are different shapes of measurement, and one gate
  cannot speak for both.

## Rationale

- **A floor is a statement about the present, and a statement that is false
  stops being evidence.** The gate's question is "does the tier still deliver
  what it used to?" A floor that says 5/19 when the tier delivers 6/21 answers
  "yes" to a question about 6/21 — and would answer "yes" just as happily to a
  tier that had grown to 6/21 *and then* lost something, because the comparison
  it makes is against a number that is not the tier.
- **The direction matters asymmetrically.** A floor that is too high fails a
  healthy run; a floor that is too low passes an unhealthy one. Only the second
  one is dangerous, and it is the second one that a one-sided floor cannot
  see. The gate has to fail when the record is wrong, not only when the world
  is.
- **The raise must not be automatic.** An auto-raising floor is a mirror of the
  thing it measures: it records whatever the last run did and calls it the
  requirement. Deliberate means a human reads the run, decides that the tier
  really is bigger now, and writes it down — and the warning on a rise is what
  asks that question out loud.
- **The instrumented tier's single configuration is what makes a rise
  informative.** Without it, a rise would be ambiguous and a finding would be
  noise. This is why the instrumented floor and the JVM floor answer the same
  question differently, and why the difference is stated rather than assumed.
- **C is the failure mode this repository keeps meeting.** A published number
  that nothing checks is a number that decays. The floor is checked; that is
  the whole value of it.

## Consequences

- **Adding an instrumented test class now fails the tier until the floor is
  updated.** That is the intended friction, and it is friction in the right
  direction: the commit that grows the tier also records what it grew to.
- **The floor must be updated whenever the suite changes**, and the update
  lives in the same commit as the change. A pull request that adds a test
  class and leaves the floor behind is a pull request whose device tier is red
  for a reason that has nothing to do with the test.
- **The smoke tier is not measured against the floor.** It runs one class on
  purpose, so its report is below the floor by construction, and asking the
  floor about it would report the smoke as a regression of the suite. The
  workflow's floor step therefore runs in the full tier only.
- **A filtered instrumented run cannot be recorded as the floor.** The writer
  warns on a rise precisely so that a run of a subset is noticed before it
  becomes the record. The instrumented task has no tag filter today; the
  warning is what keeps that true if one is added.
- **The one-sided JVM floor stays one-sided**, and the spec says so in REQ-1.
  The two gates are two measurements of two different things.

## Links

- `config/docs/instrumented-test-floor.txt` — the record and its prose
- `scripts/check-instrumented-test-floor.py` — the gate and the deliberate
  rewrite
- `scripts/tests/test_check_instrumented_test_floor.py` — the ratchet, the
  refusal, and the committed-floor test
- `scripts/check-test-runs.py` — the JVM floor, whose one-sided semantics this
  does not change
- `openspec/specs/test-execution-integrity/spec.md` — REQ-1, the JVM floor
- `docs/decisions/2026-10-20-the-maestro-cleanup-never-matched.md` — the same
  shape of defect in a different gate: a check that only fires in one
  direction
