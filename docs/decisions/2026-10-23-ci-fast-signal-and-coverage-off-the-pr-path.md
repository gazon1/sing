---
title: The pull request gets a fast signal, and the coverage measurement moves off it
status: accepted
date: 2026-10-23
tags: [ci, testing, coverage, detekt, performance]
---

# Context

A pull request in this repository paid for three things at once, and only one
of them was the verdict.

Measured on 2026-10-23, over two runs — a code pull request (run 39981868734)
and a documentation pull request (run 39982183903) — the CI of a pull request
cost **58.9 runner-minutes** and **37.3 runner-minutes**. In the code run:

| job | wall time |
|---|---|
| JVM tests, detekt, coverage | 29.4 min |
| Static gates | 6.7 min |
| ADR gate | 6.6 min |
| Android (emulator) | 4.4 min |

Inside the JVM job, `:shared:jvmTest` took **1040 s** with the Kover agent
attached and 689 s without it. The agent multiplies the run; it does not
decorate it.

Two things made that cost unavoidable on a pull request:

**The coverage measurement was on the pull-request path.** The floor, the
report and the table all ran there, so the pull request had to pay for the
measurement — and then the pull request mostly did not use it. The floor is
enforced where the coverage is computed, and the coverage was computed
everywhere, so the coverage was computed on the path where it was least
wanted.

**Detekt was judged twice.** The `static` job auto-corrected the tree it had
already checked, and the suite job's invocation judged it again — two analyses
of the same tree, of which the first one nobody read.

And a third thing was wrong in a way that no measurement showed. `enableCoverage()`
is a property of the Gradle CONFIGURATION, not of the task graph. Measured
2026-10-23: `./gw :shared:jvmTest --tests '…CapabilityFlagTest' --info` runs
the test JVM with `-javaagent:…/kover-jvm-agent-0.9.9.jar` on its command
line even though the invocation names no coverage task at all, and
`./gw :shared:jvmTest --dry-run` lists `:koverAgentSearch`. So a job that
drops `koverReport` from its invocation moves the REPORT and leaves the COST.
`config/docs/coverage-cost.properties` called `check.sh`'s `:shared:jvmTest`
"uninstrumented" at 6 min 44 s; the agent was attached, and the label was
wrong.

# Idea

Three questions, one answer each:

1. **Where does the coverage measurement happen?** Where it is enforced, and
   not on a path that does not enforce it.
2. **Where is detekt judged?** In one job, on every path.
3. **What is the fast signal on a pull request?** The tests this change can
   break — computed, and named.

# Decision

**The coverage agent is a build-level switch.** `enableCoverage()` in
`settings.gradle.kts` is conditional on the Gradle property
`coverage.enabled`, which defaults to `true`. The pull-request path passes
`-Pcoverage.enabled=false`; the coverage job passes `-Pcoverage.enabled=true`
explicitly; the local `./check.sh` keeps today's behaviour by default.

Measured, not assumed — with the opt-out, the test-worker command line has no
Kover agent (`-javaagent:…kotlinx-coroutines-debug…` is the only agent left)
and `:koverAgentSearch` is gone from the task graph; with the opt-in,
`:koverVerify`, `:koverReport` and `:koverXmlReport` are registered and the
build succeeds.

**Three jobs carry the verdicts, and the coverage job is one of them.**

| job | what it carries | when it runs |
|---|---|---|
| `coverage` | the suite under the agent, the floor, the table, the report | a push to the trunk, the schedule, a manual run, a pull request labelled `run-full-tests` |
| `detekt` | the auto-correct and the eight detekt tasks | wherever the suite runs |
| `quick` | the affected-test selection | a pull request |

An ordinary pull request runs none of the coverage measurement and reports no
coverage verdict. A pull request that asks for the full population gets one,
and a measurement below the floor fails it.

**The population is decided once.** The `changes` job decides it and publishes
it as an output; every job that runs tests reads it. The literal
`-Ptest.population="$TEST_POPULATION"` stays in the workflow, because the
parity gate reads it as an opaque token — which is what lets the decision live
in one place instead of in every job.

**The suite job names its test tasks.** `:shared:jvmTest`,
`:shared:testAndroidHostTest`, `:desktopApp:test`, `:mcp-server:test`,
`:androidApp:test` and `:detekt-rules:test` are written out. The list used to
be implied by `koverReport`, and a list that lives in the build script is a
list that has to stay in step with kover's — which is exactly how kover
silently stops measuring a module.

**The control phase runs where it is worth running.** The harness that proves
each gate can fail sabotages the gates' own declarations, so it runs when the
gate corpus changed (`scripts/**`, `config/**`, `.github/**`) or on the
schedule. Otherwise the cheap gate pass runs alone. A change to the app cannot
make a gate stop working.

**The two populations' floors are ordered.** The wider population's floor is
not below the narrower one's. `FULL_BRANCH_COVERAGE_FLOOR` rises from 20 to 23
and `FULL_LINE_COVERAGE_FLOOR` from 27 to 31; the instruction floor stays at
27. The floor gate now checks the ordering, per counter.

**The affected-test map names a population and fails loudly.** It emits
`-Ptest.population=fast` instead of a tag list of its own, refuses a base
reference it cannot resolve, intersects the selection with the population (a
class the population excludes is dropped and named), and reports an empty
selection on stdout — where the caller reads it — so that "the change claims
no test class" is distinguishable from "the comparison failed".

# Rationale

**The agent was the cost, and the report was not the cost.** The pull-request
path paid 1040 s for a suite that runs in 689 s without the agent, and paid
it for a measurement the path did not enforce. Moving the measurement to the
job that enforces it is the change; the switch in the build is what makes it
possible.

**A verdict belongs in one job.** Two analyses of the same tree are two
verdicts, and nobody reads the second. The `detekt` job holds the
auto-correct and the verdict, in that order, so the verdict measures an
already-corrected tree — the same order `just detekt-fix` and the pre-push
hook use.

**The fast signal has to be fast.** A job that names every test task a
change can break answers "how long until I know" in minutes instead of half
an hour. It runs BESIDE the suite job, not instead of it: neither waits for
the other, because a fast answer that arrives after the slow one is the same
as no fast answer.

**Three outcomes, and the third is a failure.** "The selection ran", "the
change selects no test class" and "the selection could not be computed" are
different answers, and a job that reported the last two as green would be a
job that reports a broken map as a change that breaks nothing. The map's
output contract is what tells them apart: an empty selection is a line on
stdout, a failed comparison is a non-zero exit.

**A floor taken from a wider run is unreachable by a narrower one.** The full
population's floor was 20/27 while the fast population's was 23/31 — the
wider population held to LESS than the narrower one. A reader who sees that
concludes the opposite of what a wider population means.

# Consequences

**The pull-request path is faster; the trunk pays for the measurement.** A
push to the trunk now runs the suite twice: once without the agent (the
verdict) and once with it (the measurement). That is the price of a floor
that is enforced where it is computed, and it is paid where the measurement
is wanted rather than everywhere.

**One name in the workflow is now false and stays; the other is free to
change.** The suite job's step is still called
`Gradle (tests + coverage + detekt)` and the job is still called
`JVM tests, detekt, coverage`, and neither runs coverage or detekt any
more. The STEP name is free to rename: the registry control that anchored at
it moved to the coverage job with the job graph (see the anchors paragraph
below), and `scripts/check-waiver-references.py` requires an anchor to
resolve to a step name — not to a name the workflow used to carry. The JOB
name stays, because it is a key in the published measurements
(`config/docs/gate-benchmarks.json`), and renaming it there would
invalidate them. A false name that resolves is cheaper than a dangling
anchor; the debt is recorded here rather than hidden in a rename that would
invalidate the measurements.

**The custom rules keep their own instrumentation.** `:detekt-rules` applies
the Kover plugin itself, so its tests are instrumented whatever the settings
switch says, and their branch-coverage floor (`Kover verify (detekt-rules)`)
stays on the pull-request path. That is deliberate: the path enforces that
floor, so the measurement it carries is one it acts on.

**R4's wording is wider than the job graph, and the gap is recorded here.**
R4 says "no other pull-request job shall name a detekt task", and the pro leg
of the `android` job names `:pro:detekt`. The split is deliberate — the pro
catalogue is outside the module graph the record describes, so the record
lists it apart (`config/hooks/pre-push.json`, `judged_by_ci_outside_the_module_graph`),
and the `detekt` job does not judge it. But the requirement's words do not say
so, and a requirement a reader has to reinterpret is a requirement that will
be read the other way. The wording is not reworded here — the requirement is
the contract, and a contract that is edited to match the implementation stops
being the thing the implementation is checked against. The gap is recorded
beside it instead, and the test
(`scripts/tests/test_ci_pr_path.py::DetektIsJudgedOnce`) counts the eight
modules the record names and pins the one exception, so the exception is
visible and cannot grow unnoticed.

**The registry's control anchors moved with the job graph.** The
`workflow-exit-status`, `build-output-readers` and `gradle-test-population`
controls now land in the coverage job — the coverage job is declared before
the suite job on purpose, because the controls resolve their targets by the
FIRST match in the file — and their anchors name the step they now hit. A
control whose anchor names one step while its sabotage lands in another
passes while proving nothing.

**The pull request's cost is measured, not promised.** The budget decision
keeps a number out of the contract; the number is measured on the first real
pull request with this change and recorded in `docs/testing/gate-costs.md`.

**The `cost` of the local recipe changed.** `just tests::impact` now runs the
fast population and reaches the private Gradle home through `./gw`. The same
tool should not answer two different questions locally and in CI.

# Alternatives considered

**Dropping the coverage task from the invocation and keeping everything
else** — rejected, and it is the failure this change exists to fix: the agent
attaches at configuration time, so the cost stays. The switch has to be in the
build.

**Splitting the suite job into leaves** — rejected before this change and
rejected again: the readers (the count gate, the matrix, the flake
comparison, the wiring gate) read what the invocation wrote, and splitting
them apart is what produced the withdrawn six-leaf split
(`docs/decisions/2026-10-05-ci-checks-run-in-parallel.md`).

**A new workflow file for the coverage job** — rejected: three gates read
`ci.yml` by path, and Part E of the wiring gate defines the CI surface as
`ci.yml` plus `static-gates.sh`. A new file would be a job graph the gates
cannot see.

**Running the control phase on every pull request** — rejected: the phase
sabotages the gates' own declarations to prove they can still fail, and a
change to the app cannot make a gate stop working. It stays on a gate change
and on the schedule.

**Predictive test selection** — rejected for the same reason as in
`2026-10-22-ci-fast-only-pr-full-on-schedule`: it needs a server, and its
sample is not deterministic.

# Links

- `openspec/changes/ci-fast-signal-and-coverage-off-the-pr-path/` — the
  requirement change: REQ-3 gains a floor's home, REQ-26 is new
- `openspec/specs/test-execution-integrity/spec.md` — the capability
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md` — the
  population split this change inherits
- `docs/decisions/2026-10-22-the-test-cycle-has-a-map.md` — the map this
  change makes loud
- `docs/decisions/2026-10-05-ci-checks-run-in-parallel.md` — the withdrawn
  split, and why the suite job stays one job
- `docs/decisions/2026-10-17-detekt-auto-correct-works-use-it.md` — why the
  auto-correct runs first
- `.github/workflows/ci.yml` — the job graph, and the order that is part of
  the contract
- `settings.gradle.kts` — the `coverage.enabled` switch
