---
title: A population is a name, and the report measures everything it counts
status: accepted
date: 2026-10-23
tags: [coverage, kover, gradle, ci, testing]
---

# Context

The CI change of 2026-10-22 made the test population a choice: a pull request runs the
`fast` population, the nightly runs `fast,slow`. The change was measured, the floors
were recorded per population, and the gate that asks "were the slow classes supposed to
run here?" was taught to ask by name.

Four things were still true at the end of it, and all four are the same shape: **a
number, a list, or a rule that nobody could point at.**

1. The population was a STRING in four places on three languages. `"fast,slow"` was
   written in `ci.yml`, in `settings.gradle.kts`, and in the Python gate that compares
   what a job's invocations selected. Nothing said which of them was the definition, so
   a change to one was invisible in the others.
2. The run manifest — the file that says which population a run measured — was written
   by ONE module. `shared` wrote it; `desktopApp` and `mcp-server` did not. And
   `desktopApp` is where 49 of 62 classes are slow, so the gate that asks whether the
   slow classes were supposed to run could only answer for `shared`: for the other
   modules it fell back to "did any slow class produce a report", which cannot tell
   "selected no slow class" from "deliberately fast-only".
3. The list of test tasks that feed the aggregated coverage report was written by
   hand. Two projects with tests were not in it: `:adr-core`, whose classes ARE in the
   coverage denominator, and `:adr-cli`, whose classes are not. Nothing said which of
   the two states was intended.
4. The instrumented coverage run — the one the Kover agent multiplies, 33–37 minutes —
   was priced nowhere. The table said `shared:jvmTest` 6 min 44 s, and the CI job
   "JVM tests, detekt, coverage" 31 min 29 s, and a reader who wanted to set a timeout
   had no number to set it from. One timeout in this repository was 600 seconds, on a
   task that takes 37 minutes.

A retrospective over the same session counted the waiting: 211 of 314 tool calls were
looking at a task's state (39 % `task_output`, the rest status polling in bash) and
103 were work.

# Idea

One definition per thing, in a file every language can read; a rule that says where a
thing must be declared; and a number that is published before the thing it describes
starts.

# Decision

**D1. The population is a name.** `-Ptest.population=fast` or `-Ptest.population=full`.
The tags each name selects live in `config/test-populations.properties` — one file,
read by the build (the root build script hands every module the tag list), by the
settings script (which picks the coverage floors by population) and by the Python gates
(which compare the populations a job's invocations declared). `-Ptest.tags` still works
and still wins: it is the raw knob, for a selection no population names. Passing both
is an error, not a preference — two spellings of one selection is how they drift apart.

The build derives the tag list ONCE, in the root build script, and hands it to the
modules as the property they already read. Six build scripts stay free of the
population vocabulary, and the modules' `useJUnitPlatform` blocks did not change.

**D2. The run manifest is a convention, not a copy.** The root build script writes it
for EVERY module's test task, not for `shared` alone. The manifest records the
population as well as the tags, because "which population was this?" is the question
the gate asks and the answer has to be a fact rather than an inference.

**D3. Every project with tests is measured or excluded, and the gate says which.**
`:adr-core` joined the `koverReport` list: it holds the app's ADR rules
(`com.singularity.todo.feature.ai.tools`), so its classes were in the denominator
while its tests never ran — the report scored them by whatever `:shared`'s suite
happened to touch. Its tests are green (`:adr-core:jvmTest`, 8 s) and now they run.
The denominator does not change, so the measured coverage can only rise — the recorded
floors stay satisfied, with more margin on the fast one (27 against 27.73).

`:adr-cli` is excluded from the coverage scope with `skipProjects(":adr-cli")`: it is
build tooling, it is not built during the coverage job, and the line says so instead
of leaving it to that accident. `scripts/check-coverage-task-list.py` is the gate that
keeps the two lists honest against each other.

**D4. The floors' declaration site is checked, and the check is cheap.**
`scripts/check-coverage-floor-declaration.py` asserts that `settings.gradle.kts`
declares the app floors — the three verify rules and the six constants — and that no
other build script declares a verify block under the name of the app floor. A module's
own verify block is legitimate (`detekt-rules` measures its own rules); a rule named
`app …` outside the settings is a claim the build will not keep.

**D5. The instrumented run is priced, the price is printed before the run, and the CI
timeout is checked against it.** `config/docs/coverage-cost.properties` holds the measured
cost per population; `docs/testing/gate-costs.md` publishes it; and `:koverVerify` prints
the estimate before it starts, so that nobody sets a 15-minute timeout on a 37-minute
task. `sgate gate-costs --record --coverage` re-measures it (once per population, about an
hour), and the writer of that file writes its prose as well as its numbers — a
re-measurement replaces the numbers and keeps the reasoning.

The timeout was the part that had no control behind it: the coverage job's
`timeout-minutes` was a literal, and the file that carries the measurement said in its
own header that the timeout is "set from the upper end and not from this number". The
new gate `coverage timeout` (`scripts/check-coverage-timeout.py`) reads the measured
cost and the workflow and fails when the timeout cannot cover the measurement — so the
claim is a control, and not a comment. The margin is 2×, which is what the timeout
already embodied (75 minutes against a measured 37 min 25 s) and what the runner's
4 vCPU against the host's 16 asks for; the gate's own docstring says so, and says that
the factor is a chosen margin rather than a measurement.

**D6. The Kover reader prints the number that says WHICH report this is.** The class
count. The percentages alone cannot tell a full report from a partial one: the fast
population's report of 2026-10-23 holds 4735 classes over 579,002 instructions, and the
full population's report of 2026-10-22 holds 4642 classes over 565,144 — the fast report
is not the smaller of the two universes, it is the same universe on a tree that grew.
Both readers now print it — `scripts/kover-markdown-table.py` as `--summary`, and the
Gradle task `:koverMarkdownTable` as a CLASS column.

The reader also prints the ROOT counters, and that is the number the floor is measured
against: the denominator published before this change ("2.82M instructions") was the sum
of every `INSTRUCTION` counter in the XML — the method, class, source-file, package and
root levels together — so it read five times the report's own denominator. The
percentages were unaffected, because a ratio is the same at every level, and that is how
a wrong denominator survived a real measurement.

**D6a. The measurements are recorded, and the published numbers are compared with the
record.** Both measurements — the class count, the instruction total, the branch and line
totals, the three percentages and the day each was measured — live in
`config/docs/coverage-measurements.properties`. The gate
`scripts/check-published-measurements.py` reads that file and the documents that publish
the numbers, and fails when a published class count, instruction total or measured
percentage differs from the measurement it was read from, and when a number is published
for a day the record does not hold for that population. The documents it reads are
listed in the gate itself: this ADR, the ADR of 2026-10-22, the change's tasks file, the
two Gradle scripts and the Kover reader's docstring. Before this, a published number and
the artifact it came from were never compared: the wrong denominator of 2.82M
instructions survived a real measurement because the percentages were unaffected, and a
reviewer found it by hand.

**D7. One waiter per task.** The rule in `AGENTS.md` says "one background task, not a
polling loop", and it did not say what to do afterwards: "check when necessary" is an
invitation to poll, and it has no completion criterion. The sharpened rule names the
completion criterion (the notification that the task finished) and says where the size
of the wait comes from (a measurement, not a guess).

**D8. Nothing from this session belongs in `CODING_STANDARDS.md`.** The file exists —
upstream created it on 2026-10-22, and it says what it is for: "the rules a **reviewing**
agent applies when it reads a diff … the rules that no gate checks, because a gate can
only check what has a shape". Every mistake of this session was mechanical or
informational: a missing control (D4), a missing measurement (D5), a missing pointer, a
missing provenance check. Not one of them was a judgement call that a reviewer would
have made differently — and the retrospective's own conclusion was "no candidate, and
that IS the conclusion". The one candidate it named — "a verify task that reads an
artifact must declare the task that produces it" — is not prose but a CONTROL, and it
is one now: D4's gate, and the `dependsOn("koverReport")` line that makes `:koverVerify`
read a report that exists.

# Rationale

The four defects had one cause: **a fact that lived in one place while the question
lived in another.** The population lived in four places; the manifest lived in one
module while the question lived in three; the measurement list lived in a hand-written
list while the denominator lived in the build; the cost of a run lived in a log while
the timeout lived in a workflow. Each fix moves the fact to where the question is
asked, and each gate asserts the move.

The choice of a properties file for the population mapping is deliberate: it is
readable by the Kotlin DSL, by Python and by a shell, with no parser and no code
generation. The choice of a second file for the coverage cost is deliberate too — it
is read by the BUILD, and a build reading the gate-costs baseline would make the
loop's bookkeeping an input of the build.

D5's estimate exists because the harm was concrete: a timeout of 600 seconds on a task
that takes 37 minutes kills the run after a quarter of an hour and reports a failure
that says nothing about the code. The number is now published, and the task prints it
before it starts.

# Consequences

- The build has one more public selector (`-Ptest.population`) and one fewer way to
  be wrong with it (`-Ptest.tags` and `-Ptest.population` together are an error).
- The recorded fast coverage (27.73 % instruction) can only rise from here: `:adr-core`'s
  classes were already in the denominator, and their own tests now run. The floors
  (27/23/31) stay satisfied, with more margin.
- Three gates were added, each with a positive control: the floor's declaration site
  (sabotaged by putting a verify block back into the root build script), the
  measurement list (sabotaged by removing `:adr-core:jvmTest` from it), and the CI
  timeout (sabotaged by shrinking it below the measured instrumented cost).
- **Not done: a Gradle control that raises the app floor to 99 and requires
  `:koverVerify` to fail.** The mechanical lint (D4) proves the rules are declared
  where the plugin reads them, and the CI job runs `:koverVerify` on every push — but
  the positive control itself is not affordable: on a checkout with no coverage report
  the task runs the whole instrumented suite (37 minutes) against the harness's
  600-second Gradle deadline, and a control that cannot finish proves nothing. The
  enforcement is therefore proven by the CI job and the declaration by the gate; the
  gap is recorded here rather than papered over.
- The `slow:*` sub-tags are not populations, and they do select: the named
  populations select on the primary tag (`fast` / `slow`), which every slow class
  carries, while `-Ptest.tags=slow:migration` runs one part of the slow half on
  its own. Whether a population should select by sub-tag is an open question for
  the owner, and it would move the measured floors.
- The full floor (27/20/27) is still half of its measurement (55.88/40.44/57.23). It
  was left conservative on purpose, and the gap is recorded rather than closed.
- **A published coverage number is checked against the measurement it was read from.**
  `config/docs/coverage-measurements.properties` holds the measurements per population,
  and `scripts/check-published-measurements.py` compares the class count, the instruction
  total and the three measured percentages the repository publishes with it — with the
  file and the line the number stands on. A number nobody measured, or one measured
  differently, is a finding instead of a sentence nobody checks. A number the gate
  cannot attribute to a population is a warning: it says which one, so that the gap is
  visible instead of silent.

# Links

- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md`
- `docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md`
- `docs/decisions/2026-10-22-gradle-memory-budget.md`
- `docs/testing/gate-costs.md`
- `config/test-populations.properties`
