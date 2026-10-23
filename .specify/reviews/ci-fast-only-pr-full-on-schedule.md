---
change: ci-fast-only-pr-full-on-schedule
verdict: pass
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/978
reviewed: 1cb18e3935622927120e8ac9b6f8ae9a50107a74
documents: sha256:93c85260e0cfc428f6176da71f78eaa887f33052ed6f686e3c982a1c1fcb860a
---

## What was reviewed

The two fields above were added on 2026-10-23, when the rule "a verdict names
what it reviewed" arrived, and the first version of them was wrong. Read the
rest of this paragraph before trusting either of them.

`reviewed:` is the head of `gazon1/dulse` at the time of the review — the
commit the diff below was taken against. It is a pointer, not a claim the gate
can check: the commit is not an ancestor of `main`, because the pull request
landed by squash.

`documents:` is the digest of the contract as it stands today — the change's
proposal and its spec delta. It is **not** the digest of the contract the
review saw. The contract moved afterwards: PR #986 (`745b3afa`) added REQ-25
to `specs/test-execution-integrity/spec.md` and rewrote the task list, so
`git diff 1cb18e39 origin/main --
openspec/changes/ci-fast-only-pr-full-on-schedule/` is not empty — it is 115
insertions. The review below is of PR #978's diff, and it does not judge
REQ-25; that addition was written and verified in #986, which is where this
verdict file came from.

The rule the gate enforces is that a verdict speaks for the contract it
carries, and a contract that moves leaves a verdict that speaks for a change
that is not there any more. Re-issuing a verdict after the contract moved is
a claim in a diff, and a reviewer sees it.

The diff of `gazon1/dulse` against `origin/main` for the change
`ci-fast-only-pr-full-on-schedule`, and the four documents that state what the
code must do: the change's spec
(`openspec/changes/ci-fast-only-pr-full-on-schedule/specs/test-execution-integrity/spec.md`)
and the ADRs of 2026-10-22 (CI population, Gradle memory budget, the coverage floor
in the settings).

The review was run by a separate agent with read-only access, on the diff as it stood
after the rebase onto `main` and after the reconciliation with the gates that landed
on `main` in the meantime. It was asked for defects with a location, not for a
summary of the diff, and it was told to check the claims in the documents against the
code rather than to run the build.

## Findings

1. **should-fix — the timeout justification cited a file that is not in this branch.**
   The comment on the JVM tier's timeout named
   `config/docs/coverage-cost.properties` and `docs/testing/gate-costs.md`; neither
   carries the instrumented-run timings in this branch, and the comment's claim that
   the CI runner is slower than the development host is contradicted by the run
   durations the repository itself publishes.

2. **should-fix — a comment pointed at code the change had deleted.**
   The root build script explained why the Kover DSL block does not work on the root
   project and said "see below" for a `plugins.withId("…kover")` block that this
   change removes. A reader asked to touch the floors would have re-added the dead
   mechanism.

3. **should-fix — the full population's floor claimed a provenance it does not have.**
   The label said the floor was "measured 2026-10-15 on the full run", and the
   paragraph below it said that measurement was not a full-population measurement at
   all. The file contradicted itself, and the fast floor is stricter than the full
   floor in branch and line.

4. **should-fix — the run manifest is written by one module of three.**
   The gate that decides whether the slow classes owe the reader a report reads the
   manifest's `tags=`, and only the shared module writes one. The desktop and MCP
   source sets fall back to "did any slow class execute" — the signal the ADR itself
   calls insufficient, because a filter that selects no slow class looks the same as
   a deliberate fast-only run.

5. **note — the nested call in the slot pool inherited the lock descriptor.**
   The header of `scripts/gradle-slot.sh` promises that the child never inherits the
   descriptor, but the nested path (`SINGULARITY_GRADLE_SLOT_HELD=1`) started the
   child without closing it. Only the direct child sets that flag today, so the
   impact was latent.

6. **note — no dead work.** The slot script is reached through `gw`, the stop script
   through `just gradle::stop-all`, and the pre-commit hook uses `gw` with the slot
   pool switched off, as the memory ADR requires.

## Disposition

Findings 1, 2, 3 and 5 were fixed in the same branch before the push: the timeout
comment now cites the measurement where it is recorded and claims nothing about the
runner's speed; the root build script says the block is gone and where the floors
live now; the full floor's label states what the number is — a floor the fast
population's own measurement justifies, applied to both populations — instead of
claiming a measurement that did not happen; and the nested path closes the
descriptor.

Finding 3 leaves a decision open on purpose: raising the full floor to the numbers
the full population measures (55.89 / 40.45 / 57.23) is a policy choice about how
much drift a nightly may have, not a measurement, and it is recorded as an open
decision in the ADR of 2026-10-22. The label now says plainly that the floor is not
the strictest reading of the requirement, so the next reader sees the gap instead of
a claim that hides it.

Finding 4 is real for this branch and is closed by the population-manifest change
that follows in the same series: the manifest convention moves to the root build
script, so every module writes one, and the gate reads the population from the
manifest of the source set it is asking about instead of from the executed set.

---

# Second review — the population phase

The change continued after #978 landed: the population became a name
(`config/test-populations.properties`), every module writes a run manifest, the
coverage floor moved to `settings.gradle.kts`, the report prints the class count,
and the instrumented run is priced. That diff — nine commits on top of the merged
`main` — was reviewed by a separate agent with read-only access, asked for defects
with a location and not for a summary, and told to check the claims in the ADR and
the spec against the code.

## Findings

1. **should-fix — the floor-declaration gate did not check the binding.**
   The gate asserted that three rule names and six floor constants exist in
   `settings.gradle.kts`; it never asserted that the Kover rules bind those
   constants. Replacing every `minValue.set(<floor>)` with `minValue.set(1)` left
   the gate reporting zero errors, so the app floor could be neutered with every
   gate green — the exact defect the gate exists to close.

2. **should-fix — the task-list gate could not see a module without a JVM test
   task.** The denominator was "the projects that declare a `Test` task", so
   `:androidApp` was invisible: removing `":androidApp:test"` from the `koverReport`
   list left the gate silent while androidApp's classes stayed in the denominator
   and would be scored as 0 % covered.

3. **should-fix — both docstrings claimed a control that does not exist.** They
   promised a positive control that raises the floor to 99 and requires
   `:koverVerify` to fail; the ADR of 2026-10-23 records that this control is NOT
   done, because the instrumented run costs more than the harness deadline. Two
   files in one commit contradicted each other about the same gate.

4. **should-fix — the published denominator was five times the report's own.**
   The number "2.82M instructions" was the sum of every `INSTRUCTION` counter in
   the Kover XML — the method, class, source-file, package and root levels
   together — while the report's root counter says 565 144. The percentages were
   unaffected (the ratio is the same at every level), which is why the wrong
   denominator survived the measurement it was read from.

5. **should-fix — the CI timeout was a literal, and the file said so.** The
   coverage job's `timeout-minutes: 75` was compared with nothing, while the file
   that carries the measured instrumented cost states in its own header that the
   timeout is "set from the upper end and not from this number". The pull request's
   text claimed the opposite.

6. **should-fix — re-measuring the cost erased the reasoning.** The writer of
   `config/docs/coverage-cost.properties` wrote a five-line header, so the command
   the file itself advertises replaced twenty-five lines of explanation with
   nothing.

7. **should-fix — surfaces the rename missed.** The manifest's author was still
   named as `shared/build.gradle.kts` (it is the root script), the test-run gate
   still told the reader that CI selects `-Ptest.tags=fast`, the slow-test gate
   told the reader to run `-Ptest.tags=fast,slow`, and the ADR claimed the
   `slow:*` sub-tags "select nothing" — a sentence the repository's own rule and
   `AGENTS.md` contradict.

8. **should-fix — the verdict that certified the code did not cover it.** The
   verdict file is what the "review verdicts cover every change" gate reads, and
   it covered the diff of #978. Its finding 4 was closed by "the population
   manifest change that follows in the same series" — that is, by the diff nobody
   had reviewed.

## Disposition

Findings 1 and 2 are closed by the gates themselves: the floor gate now resolves
the binding chain from each Kover rule to the declared constants, for both
populations, and the task-list gate takes the denominator from the coverage job
itself — the modules it builds, their dependencies and the application module —
so a module with tests that the list does not name is a finding. Both controls
were sabotaged to prove it: a `minValue.set(1)` and a removed
`":androidApp:test"` each make the corresponding gate fail with the file and line.

Finding 3 is closed in the docstrings: they state what the gate proves (where the
declaration lives, and that it is bound) and name the ADR that records the missing
runtime control. The gap itself stays open on purpose — the instrumented run is
too expensive to use as a probe — and it is written down rather than papered over.

Finding 4 is closed by a re-measurement of the fast population and by publishing
the report's root counters instead of the sum over the XML's nesting levels. The
corrected number is in the ADR, in the settings file and in the change's tasks.

Findings 5 and 6 are closed by making the claim true rather than by softening it:
a new gate reads the measured instrumented cost and the CI timeout and fails when
the timeout cannot cover the measurement (the sabotage shrinks the timeout to 30
minutes), and the writer of the cost file now writes the file's prose as well as
its numbers, so a re-measure replaces the numbers and keeps the reasoning.

Finding 7 is closed by the rename reaching the surfaces it had missed: the
manifest's author is the root script, the gates that advise a person name the
population, and the ADR's sentence about the sub-tags says what is true — they are
not populations, and they do select.

Finding 8 is closed by this section: the diff that closes the first review's
finding 4 has now been reviewed by a separate agent, and the findings it produced
are the ones above.
