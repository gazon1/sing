# The pull-request path's three verdicts — a model of the affected-test job, the gate proof, and the suite invocation

## Purpose and scope

This change alters three things that behave like small state machines, and each of them
has a failure mode that a green build would hide. The model below describes those
three.

**In scope**

- The affected-test job's selection and its three outcomes (the subject of
  `REQ-25`).
- The gate-proof path's decision to run the control phase, and the control phase's
  effect on the tree it validates.
- The suite job's Gradle invocation: the tasks it names, and the steps that read
  what that invocation produced.

**Outside the boundary**

- The gate registry's contents and the rules the gates enforce. Only *where* a gate
  runs changes.
- The coverage job's own measurement. It is the same shape as the suite job's
  invocation and is covered by the third table.
- The application's behaviour. Nothing in this change touches it.
- GitHub's own job lifecycle (queued → in progress → completed). This model treats a
  job's outcome as an input, not as something this change computes.

## Model boundary

The component under this model is **the pull-request path's verdict surface**: the
three jobs that report on a change (`quick`, `tests` plus `coverage`, and the gate
proof in `static`/`meta`). The state is the value each of them computes and hands
to the merge gate, and the events are the transitions those values take.

## States

### Affected-test job

| State | Meaning |
|---|---|
| `Pending` | The job is scheduled; no selection has been computed. |
| `Selecting` | The map is comparing the change against the base reference. |
| `SelectionKnown` | A candidate set exists. It may be empty. |
| `Ran` | The candidate set was non-empty, the classes ran, they passed. **Terminal.** |
| `SelectionEmpty` | The comparison succeeded and named no test class. **Terminal.** |
| `NotComputable` | The comparison could not be made. **Terminal.** |
| `Failed` | The selected classes ran and at least one failed. **Terminal.** |
| `Cancelled` | The run was superseded, or its timeout fired. **Terminal.** |

### Gate-proof path

| State | Meaning |
|---|---|
| `GatesPending` | The cheap gate pass has not run. |
| `GatesRan` | The cheap gate pass ran and its gates reported. |
| `ControlDue` | The corpus the control phase proves has changed, or the schedule says so. |
| `ControlRan` | The control phase ran and every control proved its gate can fail. **Terminal.** |
| `ControlSkipped` | The corpus did not change and this is not the schedule. **Terminal.** |
| `ControlVacuous` | The control phase ran but a control hit a line other than the one it names. **Terminal.** |

### Suite and coverage invocations

| State | Meaning |
|---|---|
| `InvocationDeclared` | The job's Gradle invocation is written down, not yet run. |
| `Running` | The invocation is executing. |
| `Reported` | The steps that read the invocation's output have run. **Terminal.** |

## Events and triggers

| Event | Trigger |
|---|---|
| `start()` | The job begins. |
| `select(baseRef)` | The map compares the working tree and the committed diff against the base reference. |
| `runSelection()` | The Gradle invocation carrying the selected classes executes. |
| `cancel()` | The workflow's concurrency group cancels the run, or its timeout fires. |
| `runGates()` | The cheap gate pass executes. |
| `diffGateCorpus()` | The changed files are compared against the gate corpus. |
| `runControl()` | The control phase executes. |
| `declareTasks()` | The suite job's invocation is written. |
| `runInvocation()` | The suite job's Gradle invocation executes. |

## Transition table — affected-test job

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `Pending` | `start()` | the event is a pull request | `Selecting` | the selection is requested | INV-10 | reads the git history; writes nothing | n/a — cannot fail yet | cancelled here, the job reports `Cancelled` | AC-01, AC-07 |
| `Selecting` | `select(baseRef)` | the base reference resolves, and the emitted command names exactly one population | `SelectionKnown` | the candidate set is held | INV-02, INV-05 | reads the git history; writes nothing | n/a — cannot fail yet | cancelled here, no selection is reported | AC-04, AC-05, AC-08 |
| `Selecting` | `select(baseRef)` | the base reference does **not** resolve | `NotComputable` | the reason is held, naming the reference | INV-05, INV-06 | none | the job exits non-zero and names the reference it could not read | cancelled here, the job reports `Cancelled` | AC-05, AC-06 |
| `Selecting` | `select(baseRef)` | the emitted command names a population **and** a tag list | `NotComputable` | the reason is held, naming the two spellings | INV-02, INV-05 | none | the job exits non-zero; the build refuses the combination anyway | cancelled here, the job reports `Cancelled` | AC-04, AC-06 |
| `SelectionKnown` | `runSelection()` | the candidate set is empty | `SelectionEmpty` | the outcome is held | INV-05, INV-06 | none — no Gradle invocation is made | n/a | cancelled here, the job reports `Cancelled` | AC-06 |
| `SelectionKnown` | `runSelection()` | the candidate set is non-empty and every class is in the chosen population | `Ran` | the outcome is held | INV-05, INV-06, INV-10 | the Gradle invocation runs the selected classes; reports are written | n/a | cancelled here, no result is reported as green | AC-06, AC-07 |
| `SelectionKnown` | `runSelection()` | the candidate set is non-empty but contains a class outside the chosen population | `SelectionEmpty` | the dropped classes are named in the report | INV-02, INV-05 | none — no Gradle invocation is made | n/a | cancelled here, the job reports `Cancelled` | AC-06, AC-08 |
| `SelectionKnown` | `runSelection()` | the candidate set is non-empty and a selected class fails | `Failed` | the outcome is held | INV-06 | the Gradle invocation runs and fails | the job exits non-zero with the failing class | cancelled here, the job reports `Cancelled` | AC-06 |
| `Pending`, `Selecting`, `SelectionKnown` | `cancel()` | the run is superseded or its timeout fires | `Cancelled` | the outcome is held | INV-06 | none | n/a | terminal | AC-07 |

## Transition table — gate-proof path

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `GatesPending` | `runGates()` | none | `GatesRan` | the gate verdicts are held | INV-07 | the gates read and write the tree; the cheap pass writes only untracked output | a red gate fails the job | a timeout mid-pass leaves the checkout dirty, and the checkout is discarded | AC-11 |
| `GatesRan` | `diffGateCorpus()` | the gate corpus changed, or the run is the schedule | `ControlDue` | the decision is held | INV-08 | none | n/a | n/a | AC-11 |
| `GatesRan` | `diffGateCorpus()` | the gate corpus did not change and this is not the schedule | `ControlSkipped` | the decision is held | INV-08 | none | n/a | n/a | AC-11 |
| `ControlDue` | `runControl()` | the phase's deadline is not reached | `ControlRan` | the control verdicts are held | INV-08 | **the phase sabotages a line of `ci.yml`, runs the gate, and puts the line back** | a control whose gate still passes fails the phase | a timeout mid-phase leaves a sabotaged file; the checkout is discarded, so nothing propagates | AC-12 |
| `ControlDue` | `runControl()` | the sabotage matched a line other than the one the control names | `ControlVacuous` | the reason is held | INV-08 | the phase sabotaged the wrong line | the phase fails — a vacuous control is worse than none | a timeout mid-phase leaves a sabotaged file | AC-12 |

## Transition table — suite and coverage invocations

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `InvocationDeclared` | `declareTasks()` | the invocation names **every** test task the job runs, and no coverage task | `InvocationDeclared` | the invocation is written | INV-01, INV-04, INV-10 | none | the declaration gate reports the omission | n/a | AC-01, AC-02 |
| `InvocationDeclared` | `runInvocation()` | none | `Running` | the run stamp is taken | INV-01 | the tests run; reports and the Gradle log are written | a red task fails the invocation; `--continue` keeps the other verdicts | cancelled here, the job reports `Cancelled` | AC-02 |
| `Running` | the invocation ends | none | `Reported` | the counts, the matrix and the floors are compared | INV-04 | the count gate compares the reports against the recorded floors | a missing report is `EXIT_NO_RUN` (3), a breach is 1 | cancelled here, the count steps never run | AC-15, AC-16 |
| `InvocationDeclared` | `cancel()` | the run is superseded | `Cancelled` | the outcome is held | INV-06 | none | n/a | terminal | AC-07 |

## Side effects

- The affected-test job reads the git history and, when it has a selection, runs a
  Gradle invocation that writes test reports into the job's own checkout. Its
  checkout is separate from every other job's.
- The control phase is the only step in the change that **mutates a file another
  step reads**: it edits `ci.yml`, runs a gate against the edited file, and puts the
  file back. The edit is confined to the `static` job's checkout.
- The suite job's invocation writes `build.log` and the JUnit reports, and the
  count step reads both. Those two steps stay in the same job.
- The coverage job writes the Kover report and the coverage table, and uploads them
  as artifacts.

## Failure transitions

| Failure | From → To | How it surfaces |
|---|---|---|
| The base reference cannot be resolved | `Selecting` → `NotComputable` | the job exits non-zero naming the reference |
| The emitted command names two population spellings | `Selecting` → `NotComputable` | the job exits non-zero; the build refuses the combination |
| A selected class fails | `SelectionKnown` → `Failed` | the job exits non-zero with the failing class |
| The control phase's sabotage hits the wrong line | `ControlDue` → `ControlVacuous` | the phase fails |
| The control phase's deadline fires | `ControlDue` → `Cancelled` | the job times out; the checkout is discarded |
| A suite task fails | `Running` → `Reported` (with a red verdict) | the invocation's exit code is the job's |
| No report was produced | `Running` → `Reported` (with `EXIT_NO_RUN`) | the count gate exits 3 and says nothing ran |

## Cancellation and shutdown

- The workflow's concurrency group (`ci-<pull-request number || ref>`,
  `cancel-in-progress: true`) cancels a superseded run. A cancelled job's outcome is
  `Cancelled`, never `Ran` and never `SelectionEmpty` — the distinction matters,
  because both are "no green result" and only one of them is a fact about the change.
- The control phase is the one step whose cancellation has a residue: a timeout
  mid-phase leaves a sabotaged `ci.yml` in the `static` job's checkout. The
  checkout is discarded at job end, so the residue cannot reach another job or the
  repository. The `static` job's timeout must still cover the phase.
- Nothing in this change is resumable. A cancelled pull-request run starts again
  from the beginning on the next push.

## Concurrency and serialization

- The affected-test job and the suite job run **in parallel**, in separate
  checkouts, with no shared state. Their Gradle invocations do not contend: each
  job has its own `GRADLE_USER_HOME` through the `setup-jvm-gradle` action.
- The control phase is serialized **after** the gates it proves, and alone, because
  it edits the tree they read. That ordering is a property of the phase, not of the
  job it lives in, and it survives the job split.
- The count step, the matrix step and the floors step are serialized **after** the
  suite invocation, because they read what it wrote.

## Recovery and retry

- A failed or cancelled run is retried by the next push, as a new run. There is no
  resumption within a run, and no state is carried between runs.
- The selection is recomputed from scratch on every run, against that run's base
  reference. A base reference that moved between runs changes the selection, and
  that is correct: it is a different comparison.
- The coverage job recomputes its measurement on every run it is scheduled for. Its
  published cost (`config/docs/coverage-cost.properties`) is the input to its
  timeout, not a cache.

## Invariants

Restated from `spec.md` as state assertions. "Checked by" names the transition whose
postcondition carries the assertion, or the gate that enforces it when the
invariant is a property of a declaration rather than of a runtime state.

| Invariant | As a state assertion | Checked by |
|---|---|---|
| INV-01 | In `InvocationDeclared`, the invocation names every test task the job runs | the transition `InvocationDeclared --declareTasks()--> InvocationDeclared`; enforced by `PartITest::test_a_covered_task_must_still_be_named_locally` |
| INV-02 | In every state reachable from `SelectionKnown`, the emitted command names exactly one population | `Selecting --select(baseRef)--> SelectionKnown` (guard); `SelectionKnown --runSelection()--> SelectionEmpty` (dropped-class branch) |
| INV-03 | The floor a run is compared against is the one belonging to the population the run measured | `Running` → `Reported`; enforced by `settings.gradle.kts:124` and the per-population floor constants |
| INV-04 | The coverage floor is enforced in the same job that measured the coverage | the coverage job's `InvocationDeclared --declareTasks()--> InvocationDeclared` and `Running` → `Reported` |
| INV-05 | `SelectionEmpty` and `NotComputable` are distinct terminal states, and no transition merges them | `Selecting --select(baseRef)--> NotComputable` and `SelectionKnown --runSelection()--> SelectionEmpty` |
| INV-06 | The terminal state is the command's status | every transition into `Ran`, `Failed`, `NotComputable` and `ControlVacuous` |
| INV-07 | The gate record and the CI workflow agree in both directions | `GatesPending --runGates()--> GatesRan`; enforced by `scripts/check-hook-gates.py` |
| INV-08 | Every control hits the line it names, and its anchor names the step it proves | `ControlDue --runControl()--> ControlRan` and `ControlDue --runControl()--> ControlVacuous` |
| INV-09 | The job that measures the coverage declares a timeout derived from the published measurement | the coverage job's `InvocationDeclared --declareTasks()--> InvocationDeclared`; enforced by `scripts/check-coverage-timeout.py` |
| INV-10 | No pull-request path carries an instrumented suite | `InvocationDeclared --declareTasks()--> InvocationDeclared` (the suite job) and the affected-test job's whole table |

## Counterexamples

1. **Re-entry — the same change, two runs, two answers.** A push is made, the run
   computes a selection and reports it; a second push arrives and the concurrency
   group cancels the first. *Expected:* the first run's outcome stays `Cancelled` and
   is never rewritten by the second; the second run computes its own selection
   against its own base reference. A cache that carried the first answer into the
   second run would report a selection for a tree that no longer exists.
2. **Parallel calls — two pushes on one pull request.** Two runs start before either
   is cancelled. *Expected:* exactly one survives; the survivor's outcome is the
   merge gate's input. Two surviving runs would each report a verdict and the merge
   gate would read two answers to one question.
3. **Cancellation mid-flight — cancelled between the selection and the run.** The
   selection is known, the job is cancelled before the Gradle invocation starts.
   *Expected:* `Cancelled`. The job must not report `SelectionEmpty` (which reads as
   "nothing to run") and must not report `Ran`.
4. **Restart — the base reference disappears between runs.** The base branch is
   renamed or deleted, and the next run's comparison cannot resolve it.
   *Expected:* `NotComputable`, naming the reference — the exact defect in the tool
   today, where the failed comparison is swallowed into an empty selection that
   exits zero.
5. **Vacuous control — the sabotage lands on the wrong line.** The workflow's text
   moves, and the control's literal `old` string now matches a different line than
   the one the control names. *Expected:* `ControlVacuous` and a failing phase. A
   green phase here would be the worst outcome in the whole change: the machinery
   that proves the gates can fail would report that it did, while proving nothing.

## Mapping to implementation

| Transition | Realised by |
|---|---|
| `Selecting --select(baseRef)--> SelectionKnown` | `scripts/test-impact-map.py` — `changed_paths()` (`:145-176`) and `map_tests()` (`:253-255`) |
| `Selecting --select(baseRef)--> NotComputable` | `scripts/test-impact-map.py` — the swallowed failure at `:157`, which the change replaces with a loud failure naming the reference |
| `Selecting --select(baseRef)--> NotComputable` (two spellings) | `scripts/test-impact-map.py` — the command builder at `:299-301` and the hardcoded `TAGS` at `:87`, which the change replaces with a named population |
| `SelectionKnown --runSelection()--> SelectionEmpty` | `scripts/test-impact-map.py` — the empty-selection message at `:341-343`, and the population intersection the change adds |
| `SelectionKnown --runSelection()--> Ran` / `--> Failed` | the new `quick` job's Gradle step in `.github/workflows/ci.yml` |
| `GatesRan --diffGateCorpus()--> ControlDue` / `--> ControlSkipped` | the new conditional in `.github/workflows/ci.yml`, driven by `needs.changes.outputs.gates` |
| `ControlDue --runControl()--> ControlRan` | `scripts/src/sgate/commands/run_all.py:562-564` — the separate `--with-controls` phase |
| `ControlDue --runControl()--> ControlVacuous` | the registry's `Edit(count=1)` — applied at `scripts/src/sgate/gate_controls.py:1030`, so the first occurrence — and `_replace`'s assertion that the sabotage was not a no-op (`gate_controls.py:52`) |
| `InvocationDeclared --declareTasks()--> InvocationDeclared` | `.github/workflows/ci.yml:520-537` — the suite job's Gradle invocation |
| `Running` → `Reported` | `.github/workflows/ci.yml:578-609` (the count gate) and `:841-842` (the floors) |

## Verification coverage

| Transition | Scenario |
|---|---|
| `Pending --start()--> Selecting` | V-01 |
| `Selecting --select(baseRef)--> SelectionKnown` | V-02 |
| `Selecting --select(baseRef)--> NotComputable` (base) | V-03 |
| `Selecting --select(baseRef)--> NotComputable` (two spellings) | V-04 |
| `SelectionKnown --runSelection()--> SelectionEmpty` (empty) | V-05 |
| `SelectionKnown --runSelection()--> SelectionEmpty` (dropped class) | V-06 |
| `SelectionKnown --runSelection()--> Ran` | V-07 |
| `SelectionKnown --runSelection()--> Failed` | V-08 |
| `cancel()` from any non-terminal | V-09 |
| `GatesRan --diffGateCorpus()--> ControlDue` | V-10 |
| `GatesRan --diffGateCorpus()--> ControlSkipped` | V-11 |
| `ControlDue --runControl()--> ControlRan` | V-12 |
| `ControlDue --runControl()--> ControlVacuous` | V-13 |
| `InvocationDeclared --declareTasks()--> InvocationDeclared` | V-14 |
| `Running` → `Reported` | V-15 |

The scenario ids are defined in `verification.md`.
