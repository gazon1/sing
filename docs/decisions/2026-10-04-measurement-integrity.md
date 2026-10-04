---
title: "Gate the magnitudes, not the booleans: coverage, skips, flakes and untested gates"
status: accepted
date: 2026-10-04
tags: [testing, ci, kover, coverage, process, gates]
---

# Context

ADR `2026-10-04-test-execution-integrity` closed one hole: a test task that runs
fewer classes than it should. It stated the generalisation explicitly — *any gate
that reports a boolean where it could report a magnitude should report the
magnitude* — and then left three instances of exactly that shape untouched.

The work in this ADR is that generalisation, applied.

## 1. Coverage was measured, and the measurement described the build

`:shared` had a Kover report, a CI job and an artifact upload. It also had no
`verification` block anywhere, so nothing failed on coverage. Worse, the number
itself was an artefact.

ADR `2026-09-25-test-jvm-heap-default` measured that the IntelliJ coverage runtime
accumulates one `ClassData` per loaded class, and that the Koog classpath alone
contributes 3,000+ of them (42% of a heap dump). The ADR's fix was to exclude
`jvmTest` from instrumentation entirely:

```kotlin
instrumentation { disabledForTestTasks.add("jvmTest") }
```

That stopped the OOM and produced a report describing only the Android host source
set. Instruction coverage read **10.5%** — a number that moved when a Gradle
property moved, not when the code did.

Kover 0.9 supports filtering at instrumentation time, which the earlier diagnosis
did not consider. `includedClasses.add("com.singularity.todo.*")` instruments only
our own bytecode; Koog's 3,000 classes are never touched, so the accumulator stays
proportional to the code under test. `forkEvery = 1` bounds it further. The same
suite now measures **23.0%** instruction, 24.4% line, 18.3% branch coverage.

The lesson is not "turn instrumentation on". It is that **an excluded test task
does not reduce coverage, it stops the measurement**, and the resulting figure looks
exactly like a coverage number until you ask which task produced it.

## 2. The artifact had never been uploaded

The CI job uploaded `shared/build/reports/kover/xml-report.xml`. Kover 0.9 writes
`reports/kover/report.xml`. The step had no `if-no-files-found: error`, so the
upload succeeded carrying nothing, every run, since the job was added. A step that
succeeds without doing its job is the same defect as a gate that passes without
testing anything — and it survived because nobody ever opened the artifact.

## 3. A skipped test is invisible to a count floor

`check-test-runs.py` compares executed class and test counts against a floor. JUnit
counts a skipped testcase inside `tests=` exactly like a passing one, so a class
annotated `@Disabled` leaves the count untouched. `TaskOutgoingLinksTest` sat
disabled for a month behind a fully green task, with an ADR explaining why the
cause had been misdiagnosed (a reviewer chasing the OOM found an unbounded loop in
`toLinksJson` instead).

The skipped count is therefore a **ceiling of zero**, not a floor. Raising it to
make the gate pass would invert its meaning, and the baseline header says so.

## 4. The gates had no tests

`scripts/tests/test_find_unwired_surfaces.py` existed and nothing ran it — not
`check.sh`, not CI. A regression inside `check-test-runs.py` would disable the
count floor silently, which is the same failure one level up. The scripts now have
56 unit tests between them, covering the failure direction (a drop, a skipped test,
a missing report, a third-party-only report) as much as the passing one, and
`check.sh` runs them in 20 ms.

## 5. Flakiness cannot be seen from one run

Two flakes in this repository's recent history — `ProjectsFlowTest` reading a draft
state before the init collector seeded it, and `TaskDetailCoordinatorGraphTest`
waiting on a 10-second real-time budget under parallel load — were each observed
once, never reproduced, and filed as prose in the deferred backlog. A run that is
green is indistinguishable from a run that is green because the test is sound.

`check-flaky-tests.py` compares two runs' JUnit XML. A status flip is the signal:
failed-then-passed is a *flake witness* even though nothing about the current run
looked wrong. CI publishes the XML on every run (previously only on failure — i.e.
only the half you do not need) and compares against the previous successful run.

The step annotates the run rather than gating it, and that is deliberate: a test
that failed here already failed the Gradle step, so failing again would change no
outcome. What it adds is the other half of the signal.

## 6. Three CI gates were advisory

`Run detekt`, `Assemble Android debug`, `Build version catalog gate` and the whole
`mcp-server` job carried `continue-on-error: true`. All four are enforced locally
already; in CI they reported findings nobody was required to fix. The `mcp-server`
job is the sharp one: it holds the profile-bootstrap identity tests, so the P0
data-corruption fix shipped with the tests that cover it unable to fail a build.

The Maestro tag check stays non-blocking, with its reason intact: it is superseded
by `MaestroFlowTagsTest` in `:shared:jvmTest`, which is blocking and covers the
same tag registry.

# Idea

Treat every verification step as a measurement with a recorded floor, and treat the
gates themselves as code that needs tests.

# Decision

1. **Filter Kover instrumentation to `com.singularity.todo.*`** instead of excluding
   `jvmTest`, and gate coverage with `scripts/check-coverage.py` against
   `config/docs/coverage-baseline.txt` (instruction/branch/line, current values
   23.0 / 18.3 / 24.4). Coverage is computed over our own packages only: a total
   over the whole Kover report is dominated by uninstrumented third-party bytecode
   and drifts with dependency bumps.
2. **Upload the artifact that exists** (`report.xml`) and set
   `if-no-files-found: error` so this cannot recur silently.
3. **Add a skipped ceiling of 0** to `check-test-runs.py`, alongside the existing
   floors.
4. **Test the gate scripts** in `scripts/tests/`, run from `check.sh` step 8 and CI.
5. **Publish JUnit XML every run** and add a flake-analysis step comparing against
   the previous successful run, with acknowledged flakes recorded in
   `config/docs/flaky-baseline.txt` and a reason required per entry.
6. **Make detekt, assembleDebug, the version-catalog gate and the mcp-server job
   blocking.**

# Rationale

**Why a floor and not a target.** A coverage *target* fails on the day it is
written, because a number nobody can reach is not a gate anyone keeps. A floor does
one job — fail on a drop — and growth needs no decision. The same rule already
governs the test-count baseline, and it is why that baseline records the *smallest*
legitimate run.

**Why own packages only.** The alternative measures `com.intellij.*` and `ai.koog.*`
alongside our code, so a Koog upgrade would read as a coverage regression nobody
caused, while our own newly-dead code would hide inside a 10% denominator.

**Why the skipped ceiling is a ceiling.** A floor of 0 is satisfied by any run; a
ceiling of 0 is satisfied only by a run where nothing was skipped. A missing test
is the thing to detect, and it can only be detected by an upper bound.

**Why the instrumentation filter beats the exclusion.** Both stop the OOM. Only one
produces a number that means something. The earlier ADR's diagnosis was correct
about the *cause* (per-loaded-class accumulation) and reached for the only tool
available at the time; a later plugin version offers a better one.

**Why the flake step does not gate.** Failing here would be failing a build that
Gradle already failed. The value is in the annotation — a flake witness is proof,
collected while the evidence still exists.

# Consequences

- `:shared:koverXmlReport` now instruments `jvmTest`, so the task takes roughly
  three times as long. It stays out of the default `./check.sh` path
  (`--if-present`) and runs in its own CI job.
- `check-doc-sizes` in CI now regenerates `DIGEST.md` first. The file is generated
  and gitignored, so on a fresh checkout it does not exist and the budget check
  would silently skip the one document whose size is generated.
- The ADR `2026-09-25-test-jvm-heap-default` is superseded on the instrumentation
  point. Its re-enablement finding (the `toLinksJson` unbounded loop) stands.
- Flake analysis needs a previous successful run on the same branch. On the first
  run, or after artifact retention expires, it prints that it was skipped — by
  design, not as a silent pass.
- Acknowledging a flake in `config/docs/flaky-baseline.txt` is a claim that its
  nondeterminism is understood. An entry that has not failed in 30 days is stale by
  the file's own rule.

# Links

- ADR `2026-10-04-test-execution-integrity` — the count floor this extends
- ADR `2026-09-25-test-jvm-heap-default` — the OOM diagnosis and the exclusion it justified
- ADR `2026-10-04-configuration-cache-hardening` — the other half of the CI honesty work
- Spec `openspec/specs/test-execution-integrity/spec.md` — REQ-1 … REQ-5
- `scripts/check-coverage.py`, `scripts/check-flaky-tests.py`, `scripts/tests/`
- `config/docs/coverage-baseline.txt`, `config/docs/flaky-baseline.txt`
- `docs/decisions/deferred-backlog.md` — `ci-gates-are-all-continue-on-error`,
  `digest-line-limit-pressure`

# Addendum (2026-10-04): a floor is only evidence about the run you just did

The ADR above builds gates on committed floors. A floor is only as good as the
numbers it is compared against, and both sources of those numbers turned out to be
weaker than they looked.

## Freshness

`check-test-runs.py` and `check-coverage.py` read whatever is on disk, and Gradle
only rewrites a source set's results when that task actually runs. A partial run
therefore leaves the other sets' XML from hours earlier, and the floor is satisfied
by a run that never happened. This is not hypothetical — it is how this branch's own
baseline got recorded wrong: `desktopApp:test` read 27 classes / 77 tests from a
leftover `-Ptest.tags=fast,slow` run while a plain `./gradlew :desktopApp:test`
executes 10 / 30, so the local loop would have reported a permanent false regression
and `check.sh` passed only because yesterday's XML was still there.

`check-coverage.py` takes `--since <epoch-seconds>` and fails a report older than it.
The test-run gate ended up needing **two** freshness modes, and the reason is a
failure mode that only appears once you run the thing twice:

- **`--since` (strict)** — the source set's newest report must postdate the stamp.
  Correct in CI, where the checkout is fresh and every test task runs.
- **`--max-age <seconds>` (tolerant)** — the report must be no older than the window.
  Correct for a local loop, and it exists because **an UP-TO-DATE test task does not
  rewrite its results directory**. The first version used `--since` everywhere, and
  the second consecutive `check.sh` failed for reusing results that were still correct.
  A strict rule that punishes a valid up-to-date build teaches people to add
  `--no-daemon` or `--rerun-tasks` rather than to trust the gate.

`check.sh` uses `--max-age 21600`; both CI jobs stamp `$GITHUB_ENV` and use `--since`.
The coverage step in `check.sh` uses neither: that report comes from a separate,
earlier task by design, so a stamp would mark it permanently stale and `--if-present`
would skip the check in silence. That step prints the report's age instead — a check
skipped by construction is indistinguishable from a check that was forgotten.

The one place that must **not** use `--since` is the coverage step in `check.sh`:
the report comes from a separate, earlier task, so a run-start stamp would mark it
permanently stale and `--if-present` would skip the check in silence. That step
prints the report's age instead, and the comment says why — a check that is skipped
by construction is indistinguishable from a check that was forgotten.

## The registry that checked nothing

`EntityMapperCompletenessTest` compares mapper field access against a hand-maintained
table, and the table is not maintained: of 11 `@Entity` classes it covered 6, and
adding a seventh would have failed nothing. The five uncovered entities were not
exempt — three of them (`TimeEntryEntity`, `AiProposalEntity`, `ProposalItemEntity`)
have real mappers, and a column omitted by a mapper is reset by `@Upsert` on every
write. This is the same "declared but never applied" shape as
`2026-09-30-testtag-registry-honesty`, in a different registry.

The test now scans the production source set for `@Entity data class` declarations
and requires each one to be in exactly one category: checked (with its mapper's field
set), skipped (join tables), unmapped (no domain model, reason required), or a
**partial projection** (the domain model deliberately omits columns — naming them and
why is the only honest option, since calling such an entity "unmapped" would be a
false statement in a file whose purpose is to be true). A fifth category appeared
during the work and was not anticipated: `CalendarSyncTaskMapEntity.toSyncedEventRef()`
omits `userId` and `syncedAt` by design, which is a partial mapper rather than a
missing one.

Six more entities are now actually checked, and the self-completeness test includes a
positive control — a synthetic case where an entity is missing from the registry — so
that the rule cannot pass simply because the scanner found nothing.

The generalisation: **a gate whose input is a hand-maintained table needs a test that
the table is complete.** Without one, the gate measures its own curation, and reports
it as coverage.

## And the one gap left open on purpose

`CachedIdentityReadArchitectureTest` originally ignored reads in a reactive call's
argument list, because `dao.watchBy(id, scopedUserId.value).map { }` reads once at
flow construction — the same one-shot semantics as an imperative write. The cost was
that `combine(u.scopedUserId.value) { }` also went unflagged, sampling a value that
can never change. The rule now separates them by position: the read must lie between
the reactive call's own name and its brace. Both shapes are pinned by fixtures, since
that distinction is the one the whole rule turns on.

# Addendum (2026-10-04, second): the coverage number described a test task, not the build

The floor this ADR introduced was honest about its own inputs, and the input it was
fed was still a measurement of the wrong thing.

## What the number actually was

Kover was applied per project. A per-project report measures that project's own
test tasks against that project's own classes — which is correct and useless for
this repository, because the tests and the code are in different modules.
`desktopApp` has 27 Compose flow tests that render the real `shared` screens; under
`:desktopApp:koverXmlReport` the whole of that execution was invisible, because the
code being executed belongs to another project. Measured, not assumed:
`:desktopApp:koverXmlReport` reported **3 classes / 220 instructions / 0 covered**
while 27 tests were rendering roughly 80k instructions of shared UI. The floor
therefore said "28 packages at 0%" about screens that had a full test suite pointed
at them.

Two tempting fixes were rejected. Writing 27+ more Compose harness tests would buy
real coverage, but only after spending the effort on a premise — that those packages
are untested — that the measurement contradicted. And a settings-level report
configuring `includedClasses = com.singularity.todo.*` was chosen instead, so that
"0% on this package" means "no test touches it" instead of "no test in the module
that owns it touches it".

## Two traps in the aggregation plugin, both verified against 0.9.9 sources

**`kover { }` in `settings.gradle.kts` does not enable coverage.**
`KoverSettingsExtensionImpl.coverageIsEnabled` is `convention(false)` and nothing in
the DSL flips it; `KoverSettingsGradlePlugin` reads it in `beforeProject` and returns
early. The failure mode is unusually well camouflaged: the build succeeds, the Kover
agent is attached to every test JVM, `build/kover/bin-reports/*.ic` files appear —
because the *subproject* plugins were still doing the instrumenting — and
`:koverXmlReport` simply does not exist. Enabling it is one call, `enableCoverage()`,
and forgetting it is invisible in every signal except the one that matters. The
comment in `settings.gradle.kts` names the symptom so the next reader can recognise it.

**`:koverXmlReport` does not run the tests.** `KoverProjectGradlePlugin` collects
whichever `Test` tasks are *already in the task graph* — `gradle.taskGraph.hasTask(...)` —
and orders them against the report with `mustRunAfter`, never `dependsOn`. On a clean
checkout the root report task therefore succeeds and writes a near-empty file, which
is the same "succeeds without doing its job" shape as the `xml-report.xml` upload
above. The root `koverReport` task exists to close that: it depends on every JVM test
task by path and only then on `:koverXmlReport`, so the graph is correct by
construction rather than by whoever remembered the right incantation.

Two smaller consequences of the same change, both confirmed by running them:
declaring `id("org.jetbrains.kotlinx.kover")` in a subproject now fails outright
("an extension already registered with that name"), because the settings plugin
applies the project plugin itself; and `KoverProjectExtension` in aggregation mode has
no `currentProject { }`, so the per-project `kover { }` blocks had to go rather than
be rewritten — the settings filter arrives in every project as a convention.

## What the number says now

Aggregated across every module's test JVM, over the same `-Ptest.tags=fast,slow`
suite that produced the old number, the floor moves **23.0 / 18.3 / 24.4 →
38.5 / 27.2 / 37.1** (instruction / branch / line). Both reports were produced on
the same branch and the same test selection; the only difference is the scope of
the report, which is the point.

The composition of the change is more informative than its size:

- **32 packages came off 0%**, worth 108,096 instructions that were previously not
  measurable at all. They are the Compose surfaces: `agenda/presentation/screen`
  (60.9%), `calendar/presentation/*` (64–81%), `projects/presentation/*` (13–77%),
  `tasks/presentation/screen` (34.0%). Those packages had 27 flow tests rendering
  them; under a per-project report the harness could not see a single instruction.
- **6 packages entered the report for the first time** — `mcp`, `mcp/schema`,
  `mcp/errors`, `mcp/pagination`, `debug`, `update` — because `mcp-server:test`
  and `androidApp:test` are now part of it. Four of them (`mcp`, `mcp/errors`,
  `mcp/pagination`, `debug`) are at 0% and `update` at 0.9%, which is a true
  statement about a module that was simply absent from the measurement before.
- **40 packages remain at 0%**, down from 67, holding 174,372 instructions. The
  largest, in order: `feature/settings/screens` (34,248), `feature/search/presentation`
  (19,448), `feature/timetracking/presentation/components` (13,124),
  `feature/auth` (10,976), `core/ui/detail` (9,876),
  `feature/calendar_sync/presentation` (9,840), `core/ui/components/sheet` (9,588).

The last list is the real output of this exercise, and it is worth being precise
about what it is and is not. `settings/screens` is 34,248 instructions of settings
Compose with no test touching it; that is now a fact about the code rather than an
artefact of which module the test lives in. It is not a claim that 34,248
instructions need 34,248 tests, and this ADR does not pretend otherwise — the
number is a prioritisation input, and the recommendation is to spend tests on the
largest genuinely-untested surfaces (`settings/screens`, `search/presentation`,
`timetracking/presentation/components`) rather than on screens that already have
flow coverage.

The floor itself is recorded from the **default local run**, without
`-Ptest.tags=fast,slow`, for the same reason `test-runs-baseline.txt` records the
`@Tag("fast")` counts: the floor must be the smallest number a legitimate run
produces, or every plain local run reads as a regression. That run measures
**25.0 / 19.1 / 24.4**, so the committed floor moved 23.0 → 25.0 instruction and
18.3 → 19.1 branch; the line floor is unchanged at 24.4. The full-suite figures
above are recorded in `coverage-baseline.txt` as a comment, so the size of the gap
is visible to whoever next regenerates the file — but the number the gate enforces
is the one a plain `./gradlew koverReport` actually produces.

## A suite that no job ran

Aggregating coverage meant enumerating the build's test tasks, and the enumeration
turned up `:detekt-rules:test` — 56 tests that no CI job and no `check.sh` step ever
runs. Four of them fail: two in `NoDirectDispatchersRuleTest` throw
`IllegalArgumentException: filename must be a file name only and not contain any path
elements` from `dev.detekt.test.utils.compileContentForTest`, and two more assert
`expected: <1> but was: <0>`. The failures reproduce with `-Pkover=false` and the
branch never touched `detekt-rules/`, so they predate this work.

They are **not** fixed here, and they are deliberately kept out of `koverReport`:
the module is `skipProjects`-ed because custom detekt rules are build tooling and do
not belong in an application-coverage denominator, and pulling a red suite into the
coverage job would convert an unrelated pre-existing defect into a new red job. The
defect stays visible in this record rather than being hidden by an exclusion — the
next person to touch `detekt-rules` should expect to find these already broken.
