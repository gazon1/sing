---
title: The app coverage floor lives in the settings, because the root has no floor
status: accepted
date: 2026-10-22
tags: [coverage, kover, gradle, ci]
---

# Context

The repository declared app coverage floors — instruction 27%, branch 20%, line 27% —
and CI had a blocking step that ran them: the "Coverage floors" step of the JVM tier,
`./gradlew :koverVerify`.

The step was green. It would have been green if the coverage were zero.

The floors were declared in the **root** build script, inside a deferred block:

```kotlin
plugins.withId("org.jetbrains.kotlinx.kover") {
    configure<KoverProjectExtension> { reports { total { xml { onCheck = true }; verify { … } } } }
}
```

That block **never ran**. Measured on 2026-10-22 by putting a `println` inside it and
running `./gradlew help`: the top-level `println` in the same file appeared, the one
inside the `withId` block did not. The reason is structural, not accidental: the root
project has no Kover extension of its own to configure. The `kover.aggregation` plugin
registers `:koverXmlReport`, `:koverHtmlReport` and `:koverVerify` on the root and reads
**its own** rules — the settings-level `kover { reports { verify { } } }`.

So the rules were never attached to anything, and the aggregate report was verified
against an empty rule set — which is satisfied by construction.

Two more symptoms of the same cause:

- `:koverLog` **does not exist** on the root project. The CI step that renders "app
  instruction coverage" into the job summary runs `./gradlew -q :koverLog … | grep …`
  and swallows the failure with `|| true`, so the summary line was silently absent.
- The floors were never subject to a control. `check-gate-wiring.py` has a sabotage
  control for the *detekt-rules* floor — which works, because that module declares the
  Kover plugin in its own `plugins {}` block and configures its own extension — and no
  control for the app floor, because nothing Python-side could have proved it.

The floors that did bite are the ones whose home is a module that applies the plugin
itself: `detekt-rules/build.gradle.kts` declares `id("org.jetbrains.kotlinx.kover")` and
its own `kover { reports { total { verify { … } } } }`, and `:detekt-rules:koverVerify`
is covered by a sabotage control that proves it fails.

# Idea

Put the app coverage floors where the plugin reads them, and let the floor belong to a
**population** rather than to the project.

The second half is the change that made the first half necessary: once a pull request
runs the fast population and the nightly runs the full one, "the coverage floor" is no
longer one number. A fast run measures less coverage than a full run, so one floor
cannot describe both — it is either trivially satisfied by the fast one or unreachable
by it. See `2026-10-22-ci-fast-only-pr-full-on-schedule.md`.

# Decision

**D1. The app coverage floors live in `settings.gradle.kts`**, in the aggregation
plugin's own block:

```kotlin
kover {
    reports {
        verify {
            rule("app instruction coverage") {
                filters { includedClasses.add("com.singularity.todo.*") }
                bound {
                    minValue.set(appInstructionCoverageFloor)
                    coverageUnits.set(CoverageUnit.INSTRUCTION)
                }
            }
        }
    }
}
```

**D2. The floor that applies is chosen by the population**, read from the same
`-Ptest.tags` property the tests use. `slow` in the list means the full population; an
absent list means the default run, which excludes `slow` and therefore measures the fast
population.

**D3. Two sets of floors, each written down from a measurement of its own population.**
The full set keeps the values measured on 2026-10-15 (27 / 20 / 27). The fast set was
measured on 2026-10-22 on a `-Ptest.tags=fast` run over the same set of tasks CI runs
(27 / 23 / 31, from 27.73 / 23.23 / 31.26), and re-measured on 2026-10-23 on the branch
that ships this decision: **28.78 / 23.92 / 32.06** over 4735 classes and 579,002
instructions. The floors stay where they were — a floor is a floor and not a target.

Both measurements are recorded in `config/docs/coverage-measurements.properties`, and
that file is where this decision's numbers are read from. The gate
`scripts/check-published-measurements.py` compares every number published here with
the measurement it was read from, so a published number that differs from the report
it came from is a finding rather than a sentence nobody checks.

The fast measurement was checked for completeness before it was written down, and the
check is what makes it a floor rather than a number: the report holds **4735 classes over
579,002 instructions** — more classes than the full run of 2026-10-22 held (**4642 over
565,144**), because the tree grew in between. The fast report is not a fraction of the
universe; it is the same universe on a later tree. A report that had measured a fifth of
the codebase would have shown a fifth of the classes too, and its percentages would have
been just as plausible.

**The denominator is the report's root counters, and a first attempt got that wrong.**
The number this decision published at first was "2.82M instructions" for both runs. It
was the sum of every `INSTRUCTION` counter in the Kover XML — the method, class,
source-file, package and root levels together — and so five times the report's own
denominator (565,144 instructions for the full run of 2026-10-22, whose root counter
is the number the report actually measures). The percentages were unaffected, because
a ratio is the same at every level: that is how a wrong denominator survived a real
measurement and looked plausible. The reader now sums the root counters, and the class
count is printed next to the percentages for the same reason it always was: the
percentages alone cannot tell a full report from a partial one.

The measurements themselves live in `config/docs/coverage-measurements.properties` —
the class count, the instruction total, the branch and line totals, the three
percentages and the day each was measured. A number that is published here and is not
in that file is a measurement nobody recorded, and the gate that compares the two says
so.

**D3a. The full floor is left conservative, on purpose.** A later full run
(2026-10-22, `-Ptest.tags=fast,slow`) measured **55.88 instruction / 40.44 branch / 57.23
line** — roughly twice the 2026-10-15 figures, which are themselves almost exactly what
the *fast* population measures today. The most likely reading is that the 2026-10-15
"full" measurement was not a full-population measurement at all.

The floor stays at 27 / 20 / 27 anyway. Raising it is a **policy** decision rather than
a measurement: the floor is the minimum acceptable coverage, and a floor of 55 would
fail the nightly on a fraction of a percentage point of drift. This change makes the
floor *real* — before it, the rule was never evaluated by anything — and an enforced
conservative floor is worth more than a strict one that is a promise. Tightening it is
recorded below as an open decision for the repository owner.

**D4. The comparison stays one-sided.** A fuller population passing a narrower
population's floor is legitimate, not a defect — so the two sets can coexist without
either being a trap for the other.

**D5. The `require` blocks move with the floors.** The claim is checked where it is
made: lowering a floor below its measured value is a configuration failure, not a
comment someone can ignore.

**D6. The "overall 45% total coverage" rule is removed.** It never ran, and its
threshold cannot be reproduced from the report: no counter in the Kover XML matches it.
The instruction, branch and line percentages of the same report are 27.73 / 23.23 /
31.26 on the fast population, and the only counter whose value is in the right range is
the class counter (55.39% today; the 57.5% recorded on 2026-10-15 is most likely that
figure) — and "the fraction of classes the suite touches" is not expressible as a
bound. A floor that cannot be derived from a measurement is not a floor; carrying it
forward as a number nobody can reproduce would be carrying a claim, not a floor.

**D7. The root build script keeps the report READING** — the markdown table that renders
`build/reports/kover/report.xml` — and loses the rule declaration.

**D8. `:koverVerify` is made to measure something.** The aggregation plugin merges the
execution data of whichever `Test` tasks happen to be in the task graph and never
depends on them, so a standalone `:koverVerify` verifies an empty report. Measured on
2026-10-22, right after the floors became real:

```
Rule 'app instruction coverage' violated: instructions covered percentage is
0.000000, but expected minimum is 27
```

The root build script now declares `dependsOn("koverReport")` on the verify task, so the
task pulls in the entry point that runs the tests and writes the report. One place, and
every caller — the CI step, `check.sh`, a bare `./gradlew :koverVerify` — measures the
same thing.

This defect is older than the floors and was invisible because of them: an empty rule
set is satisfied by an empty measurement, so the step that was supposed to enforce a
floor was green at any coverage, including none.

# Rationale

**Why the settings block and not the root.** The plugin reads the rules from there.
There is no configuration of the root project that would reach the aggregate: the root
has no Kover extension, and the aggregation plugin owns the aggregate. The old
`plugins.withId` route was not a workaround for the plugin's behaviour; it was a
configuration of an object that does not exist.

**Why the population is read from `-Ptest.tags` and not from a separate property.** One
source of truth. A `-Pcoverage.profile` could disagree with `-Ptest.tags`, and then the
floor would be checked against a run that never happened — the same failure mode the
count floors record in their own baseline file, where a floor taken from a wider run
made every plain local run look like a regression.

**Why the fast floor is higher than the full floor in two of the three units.** The
fast population is not a subset of what the full population measures in every respect:
it still runs every `testAndroidHostTest` class (that source set is exempt from the tag
filter), and it exercises the same shared code. The two numbers are close, and each is
a floor for its own measurement.

**Why the rules carry an explicit filter.** The instrumentation filter and the report
filter are different things. `instrumentation { includedClasses }` decides what gets
instrumented; the rule's own `filters` decide what the bound is computed over. Leaving
the second one implicit is how a bound ends up computed over an empty set — which is
exactly what an empty rule set does.

# Consequences

- **The coverage floor is a gate again.** It was not one before: the CI step was green
  at any coverage. This is the difference between a declared floor and an enforced one.
- **A coverage regression on a pull request now fails the build.** That is the point of
  the floor, and it is also a new failure mode for this repository. The fast floor is
  measured from the fast population, so it is reachable by a fast run; a pull request
  that adds code without tests moves the measurement, and the floor fires.
  The margin is thin — the fast floor is 27 against a measured 27.73 — so ordinary
  drift is enough to trip it. That is the repository's convention (a floor is the
  measured value, rounded down), not a choice made here, but it is the first thing to
  revisit if the new gate turns out to be noisy.
- **`:koverVerify` now runs the tests it verifies.** Before D8, a standalone
  `:koverVerify` measured nothing; the CI step therefore only measured because the
  preceding step had left the execution data on disk *and* the task graph had the test
  tasks in it. Now the task carries the dependency itself, and the two steps cannot
  drift apart.
- **The floors are now per population**, so the same pull request is measured against
  the fast floor and the nightly against the full one.
- **The "app instruction coverage" summary line is still missing** from the job summary.
  `:koverLog` does not exist on the root, and the aggregation DSL has no log block; the
  per-module markdown table (`./gw :koverMarkdownTable`) does render the same data, so
  the summary is not empty — but the line the summary step greps for is not there.
  Restoring that line is a separate piece of work.
  *Verified on 2026-10-22 by listing the root project's tasks: `koverHtml`,
  `koverHtmlReport`, `koverMarkdownTable`, `koverProjectVerify`, `koverReport`,
  `koverVerify`, `koverXmlReport`, `koverAgentSearch`, `koverGenerateArtifact` — and no
  `koverLog`. The CI step swallows the failure with `|| true`, which is why the missing
  line has never been noticed.*
- **The dropped "overall" rule leaves no gap in practice.** Its three per-unit floors
  cover the same ground with numbers that can be measured and reproduced.

# Open decision

**Should the full-population floor be raised to the measurement it actually has?**
Today the full run measures 55.88 / 40.44 / 57.23 while the full floor is 27 / 20 / 27
(D3a). The floor is enforced, so it is no longer a fiction — but it is loose enough that
a nightly whose coverage halved would still pass.

Two things have to be settled together, and neither is a measurement:

1. **How much margin a floor keeps.** The repository's convention is the floor of the
   measured value with no margin, which makes any floor a coin-flip against ordinary
   drift. The fast floor is already in that position (27 against a measured 27.73).
2. **What the full floor is for.** If it is "the coverage must not collapse", 27 / 20 /
   27 does that job. If it is "the coverage must keep growing", it is a ratchet and
   needs a policy, not a number.

Until that is decided, the floor stays where it is and this ADR records the gap rather
than closing it with a guess.

# Alternatives considered

**Keep the floors in the root build script and fix the wiring there.** Not possible: the
root has no Kover extension, so there is nothing to configure. The rules are read from
the settings-level block, and that is where they have to live.

**Implement the class-coverage question the "overall" rule was asking.** The intent was
"at least 45% of the project's classes are exercised". The aggregation DSL expresses
bounds over counters within a grouping, not a fraction of groups — and `groupBy = CLASS`
with a line-coverage bound is a *different and much stricter* question ("no class below
45% line coverage"), which this codebase does not satisfy. Imposing it would be a new
policy under the name of an old one.

**Add a Python gate that reads the Kover XML and compares the floors.** Rejected: it
would be a second implementation of a check Kover already does, and this repository's
principle is one implementation per question. The XML-reading that already exists here
is a *renderer* (the markdown table), not a verdict.

# Links

- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md` — the population
  split these floors follow
- `docs/decisions/2026-10-15-kover-floor-lives-in-the-build-file.md` — the principle
  this follows: the floor lives in the file that declares the rule
- `settings.gradle.kts` — the floors
- `detekt-rules/build.gradle.kts` — the floor that works, and the control that proves it
