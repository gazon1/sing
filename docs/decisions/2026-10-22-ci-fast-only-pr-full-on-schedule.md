---
title: The pull request runs the fast population, the schedule runs the full one
status: accepted
date: 2026-10-22
tags: [ci, testing, coverage, tags]
---

# Context

The JVM tier of CI ran the **full** test population on every pull request: every
push to a branch, every commit on a pull request, and the nightly. The full
population is 94 classes marked `@Tag("slow")` out of 501 in the three modules
the JVM tier measures — and they are the slow ones by definition, because a
class is slow when it crosses a process boundary: a real database, a real file,
a Compose harness, a spawn.

The cost of that was paid on every push, by everyone, for a signal that almost
never belonged to the change under review. A slow class that fails is a fact
about the product; it is rarely a fact about the pull request that happened to
be open when it failed. And a suite that takes long enough on a pull request
stops being run on a pull request by the people who would have run it.

The repository had already been thinking about the split — the `fast`/`slow`
tag vocabulary, the `excludeTags("slow")` default, the count floors, the
declared-class comparison — but it had never asked *which population a given
run should measure*. That question turned out to be the load-bearing one,
because three things in the repository were silently assuming "CI always runs
everything":

- **One coverage floor.** A floor describes a measurement, and the measurement
  depends on the population. One floor cannot describe both a fast run and a
  full run: it is either trivially satisfied by the fast one or unreachable by
  it.
- **A declared-class comparison scoped to the fast classes.** The reason was
  written down and was true at the time — "a slow class is excluded from every
  local run by design". Once slow classes run on a schedule, that reason dies:
  "it did not run" becomes something that has to be noticed, and the count
  floor cannot notice it, because the count floor is one-sided and a run that
  executed fewer slow classes than the previous one is still above it.
- **The scenario normaliser.** Every desktop scenario carrier is a slow class,
  so on a fast-only run the answer to "did the scenario's carrier run?" is "no"
  by construction. That is the subset the run was asked for, not a defect.

# Idea

Split the population by **event**, decide it in **one place**, and let every
later step **read** that decision:

| event | population |
|---|---|
| pull request without the label | fast |
| pull request with the `run-full-tests` label | fast, slow |
| push | fast |
| schedule (nightly, 01:00 UTC) | fast, slow |
| manual trigger | fast, slow |

# Decision

**D1. The population is decided once.** The JVM tier has a "Select test
population" step; every Gradle invocation that can run tests reads the value it
produces. This is the shape the device tier already uses for its two modes, and
the same reason applies: a tier whose steps each carry their own population can
have a step that measures a different set of classes from the one the gate
counted, and the gate then reports a verdict it cannot source.

**D2. One variable, six invocations.** The test run and the five Kover
invocations all pass the same selector. The population-parity gate compares the
*selector strings*, so a variable satisfies it by construction — all six agree —
while a literal selector added alongside them is still caught. Two jobs with two
literal selectors would have needed about 150 lines of duplicated YAML and
bought nothing over the requirement that one job measures one population.

**D3. The label works without a new commit.** `pull_request` gains `labeled` in
its `types`. Without it, a pull_request event fires only on
opened/synchronize/reopened, so the label would be recorded and nothing would
run — the same device the device tier already uses. The label is read from
**both** the `labeled` event's label name **and** the pull request's label
list: the first makes the label start a run on its own, the second keeps it
applying to the runs that follow. Reading only one of them means the label
works until the next commit and then silently stops working.

**D4. Two sets of coverage floors.** The floor that applies is the one
belonging to the population the run measured. The population is read from the
**same property** the tests use (`-Ptest.tags`), so there is one source of
truth; a separate `-Pcoverage.profile` could disagree with `-Ptest.tags`, and
then the floor would be checked against a run that never happened. Each floor
is written down only from a measurement of **its own** population, and the
comparison stays one-sided in both directions — a fuller population passing a
narrower population's floor is legitimate, not a defect.

The floors also had to move: they were declared in the root build script, in a
block that never ran, so they had never gated anything. They now live in the
aggregation plugin's own settings-level block, which is where the plugin reads
its rules. That is a separate decision, and it has its own ADR:
`2026-10-22-the-app-coverage-floor-lives-in-the-settings.md`.

**D5. The slow population is checked, not assumed.** The build records the
population it measured, and the verification gate compares the declared slow
classes against the reports whenever the run measured the slow population. A
gate that inferred the population from what happened to execute could not
distinguish a deliberate narrower run from a filter that selected nothing at
all — which is why the run writes the selector down.

**D6. The scanner answers the same question as the Kotlin gate.** The Python
scanner read `@JUnitTag("slow")` as *untagged*, because it did not resolve the
aliased import. Two real classes in the desktop module were therefore outside
every `-Ptest.tags` selection — one of the reasons the desktop scenario
carriers looked absent. The scanner now resolves the alias from the import
itself, the way the Kotlin gate already did, and a test proves the two agree on
which files alias.

**D7. The count floors by number of tests do not change.** They already
describe the fast population: the default local run excludes `slow` and runs
every fast class plus any untagged one, and there are no untagged classes in
the three modules the JVM tier measures — so the default run and
`-Ptest.tags=fast` select the same population, and a full run is above the
floor by construction.

# Rationale

**Why the split, and not simply "a faster CI".** The split is a claim about
what a pull request should answer. A pull request asks "is this change
sound?"; the fast population answers that quickly, and its failures are about
the change. The slow population answers "is the whole product still sound?",
which is a nightly question. Splitting the populations without splitting the
floors would have produced a fast run checked against a number the full run
produced — a gate that is red for a reason nobody can act on.

**Why the label exists at all.** The nightly is a fixed point in time. A
person who wants the full population *now* — because their change touches the
database schema, or because they do not trust the fast population — needs an
escape hatch that does not wait for 01:00 UTC. The label is that hatch, and it
is the same pattern the device tier uses for its two modes.

**Why the population is recorded rather than inferred.** The gate has to answer
"should the slow classes have run?" Two signals exist: the manifest the build
writes, and the executed set itself. The second alone cannot catch the worst
case — a filter that selects *no* slow class is indistinguishable from a
deliberate fast-only run — so the manifest is the authoritative signal and the
executed set is the fallback for source sets that write no manifest.

**Why the scanner had to learn the alias.** Two files in the desktop module
import the annotation under another name, because the domain class
`com.singularity.todo.feature.tags.Tag` and `kotlin.test.Test` occupy the
short name. The Kotlin gate resolved that from the import; the Python scanner
did not, and so read those two classes as untagged. The repository's principle
is one implementation per question, not one per consumer — hence a test that
reads the Kotlin pattern out of the Kotlin gate and compares the two answers.

# Consequences

- **A pull request no longer exercises the slow classes.** A change whose
  correctness depends on them needs the label, or the nightly. This is the
  cost of the split and it is real; the alternative was a slow failure landing
  on an unrelated pull request.
- **The full population still runs on every push to `main`?** No — a push runs
  the fast population too. The full population runs on the schedule, on a
  manual trigger, and through the label. The nightly is therefore the only
  regular full-population run, and its failures are reported by the existing
  nightly alert.
- **The coverage floors are per population.** A pull request that fails on
  coverage is failing against the *fast* floor. The full floors are unchanged
  from the measurement of 2026-10-15.
- **The scenario normaliser runs in partial mode on a fast-only run.** The
  per-scenario rule asks "did the carrier run?", and every carrier is slow. On
  the full population the rule stays, which is where "the scenario was claimed
  and did not run" is still a finding.
- **The flake comparison compares different populations across a population
  change.** A fast run after a full run lists the slow classes as "no longer in
  the report". The step is advisory and does not fail on it.
- **A repository that has no `run-full-tests` label has no escape hatch.** The
  label has to exist in the repository's label set; it is a repository setting,
  not a file, so it is created outside this change.

# Alternatives considered

**Two jobs instead of one variable** — a `tests-fast` job and a `tests-full`
job, each with its own literal selector. Rejected: about 150 lines of
duplicated YAML, and the parity gate would then have to accept two selectors in
one job, which is exactly what it exists to prevent.

**A path filter that widens the population automatically** — a pull request
that changes the database schema or the user interface would get the full
population without asking. Rejected for now: it makes the population a
function of the diff, which is much harder to explain than a label, and it
turns a coverage floor into a moving target. It stays a recommendation.

**Develocity Predictive Test Selection** — selects a sample of the suite per
change, which overlaps conceptually with the fast/full split. Rejected: it
needs a Develocity server, and its sample is not deterministic, which collides
with the requirement that one job measures one population and with floors that
describe a population.

**Renaming the aliased import** — impossible, and not attempted: the short
name is taken by a domain class in the same module. The scanner resolves the
alias instead.

# Links

- `openspec/changes/ci-fast-only-pr-full-on-schedule/` — the requirement
  changes: REQ-3 gains a population, REQ-23 gains the slow half, REQ-24 is new
- `docs/decisions/2026-10-22-gradle-memory-budget.md` — the CI runner's memory
  budget, which is why the CI half of this change was not optional
- `docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md` —
  where the two sets of floors live, and why they were not where they were
- `openspec/specs/test-execution-integrity/spec.md` — the capability
- `.github/workflows/ci.yml` — the decision and the six readers
