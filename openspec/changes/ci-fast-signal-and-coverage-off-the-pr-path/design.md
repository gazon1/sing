# A fast signal on the pull-request path — the design

## Summary

The pull request stops paying for a measurement it does not enforce, stops analysing
detekt twice, and gains a second, cheaper answer about the change.

Three jobs are added to `.github/workflows/ci.yml` — `coverage`, `detekt` and
`quick` — and two existing jobs lose work. The change is a rearrangement of the
workflow's job graph plus one tool's output contract. No new surface is introduced:
no new workflow file, no new gate, no new build entry point.

| Job | Before | After |
|---|---|---|
| `static` | cheap gates, the control phase, a detekt auto-fix | cheap gates; the control phase only when the gate corpus changed; no detekt |
| `meta` | the gate suite's own unit tests, `Gates are alive` | unchanged |
| `tests` | the suite under Kover, the detekt auto-fix, eight detekt tasks, the coverage floor, the coverage table | the suite without Kover, no detekt, no coverage |
| `coverage` | — | the suite under Kover, the coverage floor, the coverage table, the report |
| `detekt` | — | the detekt auto-fix and the eight detekt tasks |
| `quick` | — | the affected-test selection, run beside the suite |

The single most important structural decision is that **`coverage` is declared before
`tests` in the workflow file**, and the reason is not taste: three of the repository's
gates read that file as text, and each of them resolves "the coverage job" or "the
line this control sabotages" by taking the **first** match.

## Current architecture and affected modules

Affected files, and what each one constrains:

| File | What it holds today | Why it is in the blast radius |
|---|---|---|
| `.github/workflows/ci.yml` | ten jobs; the suite's Gradle invocation at `:520-537`; the coverage steps at `:841-878`; the detekt auto-fix at `:208-211` and `:423-426`; the eight detekt tasks at `:531`; the merge gate at `:1111-1130`; the population decision at `:357-394` | the change *is* a rearrangement of this file |
| `build.gradle.kts:385-400` | `koverReport`'s `dependsOn` — the only thing that put **seven** test tasks into the suite job's graph | the suite job stops naming `koverReport`, so it must name those tasks itself |
| `settings.gradle.kts:213-214` | `kover { enableCoverage() }` — unconditional, and the switch that attaches the agent to every `Test` task | the instrumentation is a property of the configuration, not of the task graph; the pull-request path must be able to opt out of it |
| `build.gradle.kts:470-477` | "Depending on `koverReport` is what puts the test tasks in the graph" | the sentence the change falsifies |
| `settings.gradle.kts:156-203` | the `FULL_*` and `FAST_*` floor constants and their `require` blocks | the wider population's floors rise to the narrower one's level |
| `scripts/test-impact-map.py` | the affected-test map; the swallowed base-ref failure at `:157`; the hardcoded `TAGS` at `:87`; the command builder at `:299-301`; the empty-selection message at `:341-343` | the map's output contract changes: it names a population, and it fails loudly |
| `scripts/changed-modules.py` | the module-level sibling of the map, with the same swallowed failure at `:130` | read for symmetry; not changed |
| `scripts/src/sgate/commands/run_all.py:562-564` | the control phase as a separate pass after the gates | the phase's *condition* moves; the phase itself does not |
| `scripts/src/sgate/commands/_registry.py:1248` | the `coverage-timeout` control: `old='    timeout-minutes: 75'`, `count=1` | the file's first `timeout-minutes: 75` must be the coverage job's |
| `scripts/src/sgate/commands/_registry.py` (the `count=1` argument) applied at `scripts/src/sgate/gate_controls.py:1030` | a control's `old` text is replaced at its **first** occurrence, and `_replace` refuses a no-op (`gate_controls.py:52`) | the reason for the declaration order |
| `scripts/check-coverage-timeout.py:127,142` | reads only `ci.yml`; finds the **first** job running `:koverVerify` | the reason for the declaration order |
| `scripts/check-hook-gates.py:69,109-113` | reads only `ci.yml`; derives the CI detekt module set from the raw text | the reason the detekt job stays inside `ci.yml` |
| `scripts/check-gate-wiring.py:1500-1520` | Part E's CI surface is `ci.yml` + `static-gates.sh` | the reason the new jobs stay inside `ci.yml` |
| `config/test-populations.properties` | `fast=fast`, `full=fast,slow` | the map's default population comes from here |
| `config/hooks/pre-push.json` | the eight modules CI judges; the hook's delegate | unchanged by this change — the hook keeps the detekt verdict |
| `config/docs/coverage-cost.properties` | `fast.ms=2245000`, `full.ms=2003000` | the coverage job's timeout is derived from these |

## Proposed solution

### 1. The coverage job

A new job `coverage`, declared **before** `tests` in the workflow file.

- Its Gradle invocation is today's suite-job invocation minus the eight detekt tasks:
  `koverReport` (which brings the test tasks and the report), `koverHtml`,
  `:koverVerify`, the report renderers, and `-Ptest.population="$TEST_POPULATION"`.
- Its steps are the coverage steps that leave the suite job, **with their text
  preserved**: the step named `Coverage floors` keeps its exact `run:` line
  `./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"`, because the
  `build-output-readers` control's literal `old` text is that three-line block
  including its indentation.
- Its `timeout-minutes: 75` — the number the coverage-timeout gate derives from the
  published measurement (`ceil(2245000 × 2 / 60000) = 75`).
- It runs on a push to the trunk, on the schedule, and on a pull request carrying the
  `run-full-tests` label. It does **not** run on an ordinary pull request.
- Its population decision is **not** a second copy. The `Select test population` step
  (`ci.yml:357-374`) moves to the `changes` job, which already publishes outputs both
  jobs can read, and the jobs receive it as
  `env: TEST_POPULATION: ${{ needs.changes.outputs.population }}`. The literal
  `-Ptest.population="$TEST_POPULATION"` in the `run:` lines stays **verbatim**, which
  matters twice over: `scripts/src/workflow_gradle.py:145-159` resolves a
  `$`-prefixed value as an *opaque token* — "every invocation that names the same
  variable compares equal" — and three registry controls embed that exact string in
  their `old` literals. Spelling the decision twice would also break `REQ-24`'s "one
  place" rule outright.

### 1b. The coverage agent becomes opt-out — the build-level change

**Measured, 2026-10-23.** An invocation that names no coverage task at all still runs
the test JVM with the agent attached:

```
$ ./gw :shared:jvmTest --tests '…CapabilityFlagTest' --info
Starting process 'Gradle Test Executor 1'. Command: …/java
  -javaagent:…/build/kover/kover-jvm-agent-0.9.9.jar=file:…/shared/build/tmp/jvmTest/kover-agent.args
```

`./gw :shared:jvmTest --dry-run` — again with no coverage task in the graph — lists
`:koverAgentSearch`, the agent-jar lookup the instrumented test task depends on.

The reason is the configuration, not the task graph. `settings.gradle.kts:214` calls
`enableCoverage()` unconditionally; the settings-level plugin then applies the
project-level plugin to every project, and Kover 0.9.9's `JvmOnFlyInstrumenter`
attaches the agent to `tasks.withType(Test)` through a `jvmArgumentProvider`. Nothing
in that path asks whether a coverage task is in the graph.

**So dropping `koverReport` from the suite job's invocation moves the report and
leaves the cost.** The 1040 s the plan set out to remove stays on the pull-request
path. This is the difference between a job-graph change and a build change, and the
plan needs the build change.

**The mechanism.** `enableCoverage()` becomes conditional on a Gradle property:

- the pull-request path passes the opt-out;
- the coverage job passes the opt-in, and the local `./check.sh` keeps today's
  behaviour by default.

Two things must be verified, not assumed:

1. **The opt-out really removes the agent.** The same probe above, run with the
   opt-out, must show a test-worker command line **without** `-javaagent:…kover…`.
   The repository's own documentation is not enough here and is, in fact, ambiguous:
   `settings.gradle.kts:74-81` says that with the agent attached "every test task
   runs with the Kover agent attached", while
   `config/docs/coverage-cost.properties:1-8` calls `check.sh`'s `:shared:jvmTest`
   "uninstrumented" at 6 min 44 s. The probe settles which is true for the build as
   it stands — **the agent is attached** — and the cost table's label is wrong.
2. **The opt-out does not break the build.** With the agent off, the root coverage
   tasks are not registered (`settings.gradle.kts:74-77`), so the pull-request path
   cannot name them — which is what the change wants — while the coverage job, which
   opts in, still can.

The cost table's 6 min 44 s therefore needs re-measuring: it was taken from a run
that, as the build stands, was instrumented. That is the plan's step 0, and this
change cannot claim the saving without it.

### 2. The suite job

- The Gradle invocation drops `koverReport`, `koverHtml` and the eight detekt tasks,
  and **names the test tasks explicitly**:
  `:shared:jvmTest`, `:shared:testAndroidHostTest`, `:desktopApp:test`,
  `:mcp-server:test`, `:androidApp:test`, `:detekt-rules:test`.
  `:adr-core:jvmTest` is not among them: the ADR job owns it (`ci.yml:745`), and
  naming it here would put a module in two jobs' worth of coverage.
  `:detekt-rules:test` **stays**: it is named there explicitly today (`ci.yml:529`),
  and dropping it would move the custom rules' own tests off the pull-request path
  entirely — they would run only where the coverage job runs.
  The expected set is not the implementer's: it is the coverage entry point's
  `dependsOn` list (`build.gradle.kts:390-399`) minus the tasks another job owns.
- **The invocation's non-test parts stay.** `:mcp-server:jar` must be in the same
  invocation (`ci.yml:434-437`): `McpServerEndToEndTest` resolves
  `build/libs/mcp-server.jar` and *skips itself* when the jar is absent — a silent
  pass, not a failure. `:shared:verifyToolSurface` is the tool-surface gate and
  stays. Only `koverReport`, `koverHtml` and the eight detekt tasks leave.
- The step's **name is load-bearing and the design must say what happens to it**.
  `scripts/src/sgate/commands/_registry.py:1215` anchors the `workflow-exit-status`
  control at `.github/workflows/ci.yml#Gradle (tests + coverage + detekt)`, and
  `scripts/check-waiver-references.py:31-38` requires that anchor to resolve to a
  **step name**, not to a string that occurs somewhere in the file. After the change
  the step runs neither coverage nor detekt, so the name is false; renaming it
  breaks the anchor. The design therefore keeps the name and re-points the anchors
  whose steps moved — a false name in a step that is the anchor of a waiver is
  cheaper than a dangling anchor, but it is a debt, and it is recorded as such.
- `GRADLE_TASK_PARITY` has two entries that go stale: `:shared:jvmTest` and
  `:desktopApp:test` are `covered` today, which means "CI reaches it without writing
  the task name" (`scripts/check-gate-wiring.py:2333-2337`). After the change CI
  names both, so the entries become `both`. The gate passes either way — a `covered`
  task only has to be named by `check.sh` (`:2343-2349`) — but an entry that stops
  describing the graph is a declaration that rots.
- The steps that read the invocation's output stay in the job, because they read it:
  the count gate (`:578-609`), the matrix (`:651`, `:666`), the flake comparison
  (`:691`) and the wiring gate (`:909-913`). **This is the coupling that broke the
  withdrawn six-leaf split**, and it is why the suite job is not split further.
- The detekt auto-fix step leaves, and so does the job's `contents: write`
  (`ci.yml:301-304`): that permission existed for the auto-fix branch and for nothing
  else.
- The job keeps its `timeout-minutes: 75`. The number came from the instrumented
  measurement, and the un-instrumented cost is unmeasured (the plan's step 0). A
  ceiling that is too generous wastes nothing; a ceiling that is too tight truncates
  a slow run and reports a failure about the clock.


### 3. The detekt job

A new job `detekt`, carrying the two things that are detekt's verdict and nothing
else. It runs wherever the suite job runs — a pull request, a push to the trunk, the
schedule — because detekt is judged on every path, not only on pull requests:

1. `bash scripts/ci/detekt-auto-fix.sh "detekt" "${{ github.run_id }}"` — the
   auto-fix, with `contents: write` so the corrected tree reaches the
   `detekt-auto-fix/detekt/<run-id>` branch.
2. The eight detekt tasks, as one invocation, for the verdict.

The order is the order the suite job uses today: the auto-correct first, the verdict
second, so the verdict measures an already-corrected tree. The `static` job's
auto-fix goes away, which removes the second analysis of the same tree.

The `detekt` job needs `permissions: contents: write` — for the auto-fix branch and
nothing else. The `static` job's `contents: write` (`ci.yml:96-101`) existed for the
same reason and goes with it; the job keeps `actions: read` for the Gradle cache.

### 4. The affected-test job

A new job `quick`, running beside the suite job.

- **Trigger:** `github.event_name == 'pull_request'`. On a push to the trunk the
  base reference *is* the head, so the comparison is empty and the job would report
  "the change selects nothing" for every push — a fact about the push, not about the
  change.
- **Checkout:** `fetch-depth: 0`, so the base reference exists. The map's default
  base is `origin/main`.
- **Permissions:** `actions: read`, alongside the workflow's default `contents: read`
  (`ci.yml:31-33`). The `setup-jvm-gradle` action's cache restore needs
  `actions: read` (`ci.yml:96-100`); without it the restore is refused and the job
  resolves every configuration from scratch — 113 s on a good day, killed at the
  budget on a bad one, with the same tree and the same code (`ci.yml:114-119`).
- **Selection:** `python3 scripts/test-impact-map.py --command --population fast`.
  The tool's output contract changes (below).
- **Outcome:** the job's step reads the map's output and reports one of three
  outcomes — the selection ran and passed, the change selects no test class, or the
  selection could not be computed.

### 5. The map's output contract

Three changes to `scripts/test-impact-map.py`, each removing a silent failure:

1. **A named population instead of a tag list.** The tool emits
   `-Ptest.population=fast` instead of `-Ptest.tags=fast,slow`. The population is
   configurable with a `--population` flag whose default is the first population in
   `config/test-populations.properties`.
2. **A selection that cannot be computed is an error.** A base reference that does not
   resolve makes the tool exit non-zero with a message naming the reference. Today
   the failed comparison is swallowed (`:157`) and the tool exits 0 with an empty
   selection.
3. **A selection is intersected with the population.** A selected class whose tag is
   outside the chosen population is dropped from the command and named in the report.
   Without this, `--tests SlowClass` under the fast population runs nothing and
   reports green.

The command the tool builds also names the Gradle entry point, and today that is
hardcoded to `./gradlew` (`:299`). That is right in CI — the `setup-jvm-gradle`
action has already arranged the Gradle home and the cache — and wrong for the local
recipe, where `./gradlew` reaches the shared `~/.gradle` that the repository's own
`./gw` wrapper exists to avoid (`gw`: "Parallel git worktrees share `~/.gradle` by
default, and sharing it has two costs that are easy to mistake for something else").
Since this change rewrites that line anyway, the entry point becomes overridable and
the local recipe passes the private one.

The tool's local entry point (`just tests::impact`, `.just/tests/mod.just:65-74`)
follows the same change: it now runs the fast population rather than fast and slow.
That is a behaviour change for the local recipe, and it is the right one — the same
tool should not answer two different questions locally and in CI.

### 6. The control phase's condition

`sgate run-all --with-controls` runs when the **gate corpus** changed, or on the
schedule. Otherwise the cheap `sgate run-all` runs.

The gate corpus is `scripts/**`, `config/**` and `.github/**`. The `changes` job's
existing `scripts` filter covers `scripts/**`, `.github/**` and `docs/**` — it does
**not** cover `config/**`, which holds the gate's own declarations
(`config/hooks/pre-push.json`, `config/test-populations.properties`,
`config/docs/coverage-cost.properties`). A new `gates` output is added to the
`changes` job rather than widening the existing `scripts` filter, because the
existing filter also decides whether the `static` job runs at all, and widening it
would change a gate's trigger rather than this change's condition.

The control phase's **position** does not change: it still runs after the gates and
alone, because it edits the tree they read.

### 6b. The controls' sabotage points and their anchors

The design's ordering argument covers only half of what a control is. Each registry
control has two parts: the `old` text it replaces, and the `tracked_by` anchor that
says which step the control is about.

The `old` text resolves by **first occurrence** — the registry declares `count=1`
(`_registry.py:1248`), which `_replace` passes to `text.replace(old, new, count)`
(`gate_controls.py:53`) — so declaring `coverage` before `tests` is enough to
move a sabotage onto the coverage job's line. The anchor does not move with it:
`scripts/check-waiver-references.py:31-38` requires a `tracked_by` of the form
`path#anchor` to resolve, and for a workflow the anchor must be a **step name**.

Three of the four controls that read `ci.yml` carry such an anchor:

| Control | `old` text | `tracked_by` |
|---|---|---|
| `workflow-exit-status` | `set -o pipefail` + `./gradlew --continue \` | `ci.yml#Gradle (tests + coverage + detekt)` |
| `build-output-readers` | the three-line `Coverage floors` block | `ci.yml#Normalise desktop results into the scenario matrix` |
| `gradle-test-population` | `run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"` | `ci.yml#Normalise desktop results into the scenario matrix` |
| `coverage-timeout` | `    timeout-minutes: 75` | `ci.yml#Normalise desktop results into the scenario matrix` |

So the change **does** need edits in `scripts/src/sgate/commands/_registry.py`: after
the move, each control's anchor must name the step its sabotage now lands in, and
the anchors that named a step in the suite job must be re-pointed at the coverage
job. The anchors themselves must keep resolving — a dangling anchor is a pointer to
nothing, and it is the only thing a waiver has instead of a date
(`scripts/check-waiver-references.py:25-27`).

The suite job's own Gradle step keeps its name, so
`ci.yml#Gradle (tests + coverage + detekt)` still resolves; the other three
anchors move with the `Coverage floors` block.

### 7. The floors

- The `Kover verify (detekt-rules)` step (`ci.yml:887-888`) keeps its **exact two
  lines**: the `waiver-references` control's `old` text is that block verbatim
  (`_registry.py:1274`), and `_replace` refuses a no-op — a reformat would make the
  control phase error rather than fail quietly. This step stays in the suite job; it
  is a gate about the custom rules, not about the app's coverage.

`FULL_*` rises from 27/20/27 to 27/23/31 — the `FAST_*` level. The measured
full-population values are 55.88/40.44/57.23, so the new floor is reachable. The
change is a policy: a wider run is judged at least as strictly as a narrower one.

### 8. The merge gate

`ci-gate`'s `needs` gains `detekt` and `quick`. Its accounting is unchanged: a
skipped job is a valid outcome, a failed one is not.

### 9. The record

`config/hooks/pre-push.json` does not change. The eight modules CI judges are the
same eight, the detekt job judges them, and the hook keeps its own local verdict
over the modules the change reaches. The record stays true, and
`scripts/check-hook-gates.py` keeps agreeing with it.

## Alternatives considered

**Alternative 1 — a new workflow file for the new jobs (`ci-fast.yml`).**
*Lost.* Three gates read `.github/workflows/ci.yml` by a hardcoded path or by a
definition that names it: `check-hook-gates.py:69`, `check-coverage-timeout.py:127`,
and Part E's CI surface in `check-gate-wiring.py:1500-1520`. A new file would mean
teaching three gates about a second surface, and `check-hook-gates.py` would
immediately read the CI detekt module set from a file that has no detekt job — the
record would stop matching the workflow. A new job inside `ci.yml` costs nothing
here.

**Alternative 2 — split the suite job into leaves, the way the withdrawn six-leaf
split did.**
*Lost.* `ci.yml:213-224` records that this was tried and withdrawn: it "made a green
run that measured the wrong thing". Three couplings broke it — the run stamp, the
count and coverage floors, and the flake comparison — because each reads results
that one job produced. The suite job keeps its steps that read its own output. The
splitting this design does — coverage and detekt out — cuts along a line that does
not cross those three couplings.

**Alternative 3 — keep the coverage on the pull-request path but drop the Kover
agent.**
*Lost.* The capability requirement says it outright: "Excluding a test task from
instrumentation does not reduce coverage, it stops the measurement — and the
resulting number describes the Gradle configuration rather than the code". The
instrumentation *is* the cost, and the report without it is not a report.

**Alternative 4 — make the affected-test job the only signal on the pull request.**
*Lost.* An affected-test map is a candidate set, not a verdict, and it is blind to a
whole class of changes: a change under `scripts/`, `config/`, `.github/`, `docs/`,
`openspec/` or `.specify/` selects no test class at all. If the full suite were
dropped from the pull-request path, those changes would reach the trunk untested.
The suite stays; the affected-test job runs beside it.

**Alternative 5 — run the control phase in its own job on every pull request.**
*Lost.* It is the same 400 s, moved rather than removed, and it adds a job. Moving
it *off* the pull-request path, to the gate corpus and the schedule, removes the
cost from the pull request without losing the proof.

**Alternative 6 — keep `koverReport` in the suite job's invocation and let the
coverage job run a second instrumented suite.**
*Lost.* It would run the suite twice per push: once without Kover for the pull
request and once with it for the coverage. The coverage job already runs the
instrumented suite, so the suite-job invocation only needs the tasks it runs.

**Alternative 7 — leave the population decision in the suite job and give the
coverage job its own copy of the same expressions.**
*Lost.* That is the second copy `REQ-24` forbids: "a tier that decides the population
in several places has several populations, and the disagreement between them is
invisible until one of them measures something the others did not." The two copies
would agree until a new trigger appeared in one of them — a label, a schedule — and
then the coverage job would measure a population the suite job never ran.

## Why this solution is the simplest

The change adds three jobs to one file, edits one tool's output contract, adds one
output to the path filter, and moves two sets of floor constants. It adds **no new
surface**: no new workflow file, no new gate, no new build entry point, no new
dependency.

Each alternative adds a surface or removes a judgement:

- A new workflow file adds a surface three gates must learn.
- A leaf split of the suite job adds jobs and crosses three couplings.
- Dropping the Kover agent keeps a surface and makes the number wrong.
- Dropping the full suite removes a judgement.
- Moving the control phase to its own job adds a job and moves the cost instead of
  removing it.

The one part of the design that looks like extra work — naming the suite job's test
tasks explicitly, which `koverReport` used to do — is not extra work at all. It is
the *same list*, written where the job now needs it, and it removes a dependency on
a build entry point that exists for a different purpose. `build.gradle.kts:471-472`
says it plainly: "Depending on `koverReport` is what puts the test tasks in the
graph". A job that does not need the coverage should not get its tests through the
coverage's entry point.

## Component and dependency changes

| Change | The defect it removes |
|---|---|
| New job `coverage` | the pull-request path pays 75.6 % of its Gradle step for a measurement it does not enforce; a coverage regression reaches the trunk instead of being caught at the pull request |
| New job `detekt` | detekt is analysed twice per pull request — once in `static`, once in `tests` — and the duplicate costs 196 s of runner time for the same tree |
| New job `quick` | the first useful answer to "what could this change break" arrives only after the whole suite, 29 minutes later |
| The suite job names its test tasks | the suite's test tasks are reachable only through a coverage entry point the job no longer uses; a job that names some tasks and inherits the rest has a size that depends on a task it does not name |
| The map names a population instead of spelling tags | the tool answers a different question than the one asked — it selects the fast and slow half together, so a job meant to answer in minutes measures the population the schedule measures, against the wider population's floor |
| The map fails loudly on an unresolvable base reference | a comparison that failed is indistinguishable from a change that breaks nothing, and the tool reports it with exit code 0 |
| The map intersects the selection with the population | a selected class outside the population produces a job that ran nothing and reports green |
| The `changes` job gains a `gates` output | the gate corpus includes `config/**`, and the existing filter does not cover it, so a change to a gate's own declaration would not trigger the proof that the gate can fail |
| The `changes` job gains a `population` output | two jobs would each decide the population, and `REQ-24` requires one place that decides it |
| The registry's control anchors are re-pointed at the steps they now sabotage | a control whose anchor names one step and whose sabotage lands in another proves a step it does not claim — a green proof of nothing |
| The `GRADLE_TASK_PARITY` entries for the two test tasks become `both` | "CI reaches it without writing the task name" stops describing the graph once CI names it |
| The `FULL_*` floors rise to the `FAST_*` level | a wider run is judged more leniently than a narrower one |
| The merge gate names the new jobs | a pull request could merge while a job that was supposed to judge it had not reported |

No new dependency is introduced. The map reaches `scripts/scanning.py`'s
`read_tag` for the population intersection — a function that already exists in the
repository and is already used by the run-floor gate.

## State-model changes

The state model is in `model.md`. Three components change:

- The affected-test job gains the three terminal outcomes it must distinguish. The
  transition that matters is `Selecting --select(baseRef)--> NotComputable`, which
  today does not exist: the failed comparison is swallowed.
- The gate-proof path gains the `ControlSkipped` terminal state.
- The suite invocation's declaration gains the guard "every test task the job runs is
  named".

No terminal state gains a path back. No state is added to a component that had none.

## Persistence and compatibility impact

- **No data migration.** Nothing is stored; the change is a workflow and a tool's
  output format.
- **The published numbers stay valid.** The coverage measurement is the same
  measurement — the same test tasks under the same agent — so
  `config/docs/coverage-cost.properties` and
  `config/docs/coverage-measurements.properties` do not change. The *floor* changes,
  not the measurement.
- **`check.sh` keeps naming the same tasks.** `:detekt-rules:koverVerify` is named by
  both surfaces today (`ci.yml:884-886`); the change does not move it.
- **The hook's record stays true.** The eight modules CI judges are unchanged.
- **The step names that waivers point at stay put.** The anchors are in the suite and
  gate-tooling jobs; the change does not rename those steps.
- **The `run-full-tests` label's meaning widens.** It already selects the full
  population; after the change it is also how a pull request gets a coverage
  measurement. That is a change to a label's contract and it is recorded as an open
  question, not decided here.
- **The workflow's text is a compatibility surface.** The four registry controls
  resolve their targets by the first match, so the declaration order is part of the
  contract. That is documented in the workflow itself, next to the job it constrains.

## Testing strategy

The strategy is in `verification.md`. In short:

- Twenty-two scenarios, each bound to a transition or a declaration, each with a
  deterministic reproduction command.
- The unit-level evidence is the Python gate suite; a new file
  `scripts/tests/test_ci_pr_path.py` pins the pull-request path's shape.
- The regression net is the nine gates that already read the workflow, each of which
  already has a control that proves it can fail.
- The timing is **not** a criterion. It is a measurement, taken from the API after the
  first real pull request.

## Rollout or migration

Two commits, in this order, because the second depends on the first:

1. **The map's contract.** The population naming, the loud base-reference failure,
   the population intersection, and the existing test
   `test_the_command_names_the_tag_selection_that_runs_the_slow_half` replaced by
   `test_the_command_names_a_population_not_a_tag_list`. Nothing in the workflow
   changes, so the pull-request path behaves exactly as before — the map is only
   called locally.
2. **The workflow's job graph.** The three new jobs, the suite job's explicit task
   list, the control phase's condition, the floors, the merge gate's `needs`, and the
   new gate file.

The floors and the decision record could ride in either commit; they ride in the
second, because the second is the commit that makes the floor's locus meaningful.

The first pull request with the change is the measurement. Its runner-minutes and its
wall clock are compared against the 58.9 runner-minutes measured in
`investigation.md`, and the result is recorded — as a measurement, not as a criterion.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| The suite job runs fewer tests than before, because a task was forgotten in the hand-written list | the gate-wiring parity check already refuses a task CI reaches only through a covered entry point (`PartITest::test_a_covered_task_must_still_be_named_locally`); the new shape gate names every test task the job runs |
| A registry control hits a line other than the one it names, and the phase proves nothing | the coverage job is declared before the suite job, so all four controls resolve to the coverage job's lines; V-13 asserts the vacuity is reported rather than passed |
| The affected-test job reports "nothing to run" because its comparison failed | the tool now fails loudly; the job distinguishes the two; V-03 asserts the distinction |
| The affected-test job runs nothing and reports green, because every selected class was outside the population | the selection is intersected with the population and the dropped classes are named; V-06 asserts it |
| The coverage job's timeout is derived from a measurement taken on another machine | the number comes from the published measurement, exactly as the suite job's 75 does today; the gate recomputes it |
| The change makes the pull request *slower* rather than faster | the timing is measured on the first real pull request and recorded. The budget decision keeps the number out of the contract, but the measurement is taken anyway |
| The affected-test job's full-history checkout is expensive | `fetch-depth: 0` on one job, against a checkout that is otherwise shallow; the alternative — fetching one base ref — is a second command and a second failure mode |
| The change lands before `ci-fast-only-pr-full-on-schedule` is archived, and the other change's `REQ-3` replaces this one's clause | the dependency is recorded in `.openspec.yaml` and in `spec.md`'s Scope; the archive order is a decision, not an accident |

## Task dependencies

```
T001 (the build's opt-out) ── T006 (the workflow) ─┬─ T007 (the coverage job)
                                                   ├─ T008 (the detekt job) ─┐
                                                   ├─ T009 (the quick job) ──┴─ T010 (the merge gate)
                                                   ├─ T011 (the control phase's condition)
                                                   ├─ T012 (the controls' anchors)
                                                   ├─ T013 (the floors)
                                                   └─ T014 (no coverage verdict)
T002 ─┬─ T003 ── T005
      └─ T004
T015 (the decision record and the documentation) ── T008, T011, T012, T013
```

**T001 is the build's opt-out and every workflow task depends on it**: the
pull-request path cannot opt out of the agent before the opt-out exists. T002–T005
are the tool's contract; they are sequential because they touch one file, and they
depend on nothing else. T006–T014 are the workflow; they touch the same file and
are therefore sequential as well — T006 is their common precondition. T015 is the
record, and it depends on the tasks whose decisions it records.

The rollout follows the same order: the build's opt-out and the tool's contract land
first (neither changes the pull request's behaviour on its own), and the workflow
lands second.

## Non-goals

- Branch protection on `main`. Separate act, separate risks.
- Making the full suite faster.
- Making the affected-test signal a verdict.
- Changing what the gates enforce.
- Changing the gate registry's contents, or adding a gate.
- The device tier, the end-to-end tier, and the release workflow.
- The application's behaviour.

## Validation commands

```bash
# The gate suite — the unit-level evidence for every scenario.
PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests

# One scenario.
PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests \
  -k 'test_a_missing_base_ref_is_refused'

# The gates that read the workflow as text.
python3 scripts/check-coverage-timeout.py --quiet
python3 scripts/check-gradle-test-population.py
python3 scripts/check-build-output-readers.py
python3 scripts/check-hook-gates.py
python3 scripts/check-workflow-exit-status.py
python3 scripts/check-ci-workflow-shape.py
python3 scripts/check-waiver-references.py
python3 scripts/check-coverage-floor-declaration.py

# The control proof, on the changed workflow.
python3 scripts/check-gate-wiring.py --can-fail
sgate run-all --with-controls

# The whole gate corpus, the way CI runs it.
PYTHONPATH=scripts/src python3 -m sgate run-all

# The capability delta still validates.
npx --yes @fission-ai/openspec@1.14.0 validate \
  ci-fast-signal-and-coverage-off-the-pr-path --strict

# The measurement, after the first real pull request.
gh api repos/gazon1/sing/actions/runs/<run-id>/jobs \
  --jq '.jobs[]|"  \(.name) \(.conclusion)"'
```
