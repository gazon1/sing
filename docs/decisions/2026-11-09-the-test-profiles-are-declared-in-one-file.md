---
title: "The test profiles are declared in one file, and they reference the populations"
date: 2026-11-09
status: accepted
tags: ["tooling", "testing", "ci"]
---

# The test profiles are declared in one file, and they reference the populations

## Context

The repository had one vocabulary for "which tests run": the population.
`config/test-populations.properties` names a set of JUnit tags, and CI passes
it as `-Ptest.population=fast` or `-Ptest.population=full`.

Everything else that decided *what a surface runs* had no name at all. The
JVM tier was ten Gradle tasks spread across six modules, written out in the
workflow, in the coverage floors, in the floor contracts and in the docs — each
copy separately, each free to drift. The instrumented tier was one task, the
Maestro tier was a set of flows, and "the tests" meant "the fast population" on
a pull request and "the whole JVM tier" on a laptop, with no word anywhere for
the difference.

That is what issue #2663 asked to fix: define the run profiles and the
outcomes, so that a person reading a failure knows *which* run produced it.

The populations file also had to stay where it was. Its contents are a
contract with `TestTagCoverageTest` and with the coverage floors, and
rewriting it into a "profile" file would have made the tag vocabulary
indirect for every reader that only cares about tags.

## Idea

- Name the *sets of tasks* too, in a file of their own, and let a profile
  **reference** a population rather than restate it.
- Keep one reader for both files, next to each other, so that the two stay
  consistent by construction.

## Decision

**The profiles live in `config/test-profiles.properties`.** The format is the
populations file's format: `profile=task,task` for the tasks, and
`profile.property: value` (the properties-file **colon** separator) for the
properties. The two separators are the contract — Python readers split on `=`,
and a property written with `=` would be read as a profile named after the
property.

The properties that exist are `tier`, `population`, `reported`, `deferred`,
`conditional` and `reports`. `device` and `e2e` have
no population, and that is not an omission: the instrumented tests carry no
`@Tag` at all, and a Maestro flow's tag (`smoke`, `regression`) lives in a
namespace JUnit knows nothing about. A population there would be a name for a
selection nothing implements.

**A profile's tasks are split by what their report does.** Every task of a
profile is in exactly one of three lists:

- `reported` — the task ran and wrote a report;
- `deferred` — the task ran and writes its report after the evidence index is
  built (the ADR modules run in their own Gradle invocation, below the index
  step);
- `conditional` — the task ran and wrote nothing, because the population it
  ran with selected no test of it (`mcp-server:test` has one test and that
  test is `slow`, so under `fast` the task writes no XML at all).

The three are a **partition**, and the gate says so. One list cannot express
"the task ran and selected nothing", and the workflows used to keep a second,
hand-written list of expected tasks to work around exactly that — a list that
drifts away from the declaration it claims to follow. `conditional` is the
one that lies if you forget it: a task that ran and selected nothing is not a
task that did not run.

**`reports` says where the profile's reports live.** The three tiers write
them in three different places, so a reporter asked for `device` has to look
in the instrumented one.

**The reader is `scripts/src/workflow_gradle.py`**, next to `load_populations`:
one module owns the vocabulary of "what a run is". It exposes `load_profiles`,
`profile_tasks`, `profile_population`, `profile_reported`, `profile_deferred`,
`profile_conditional`, `profile_globs`, `profile_tier` and `is_report_task`;
`is_test_task`
now delegates to it, so the two notions cannot disagree about what a test task
is.

**The gate is `scripts/check-test-profiles.py`.** It holds eleven rules, and
each one is a drift that has already happened or is easy to write:

1. the vocabulary is exactly `fast`, `full`, `device`, `e2e`, `all`;
2. a task is `module:task`, and the module exists (or is the declared
   pseudo-module `maestro`);
3. `all` is exactly the union of the others;
4. `fast` and `full` differ only by population;
5. a profile that is not tag-selected declares no population;
6. every test task a surface runs is declared in `all`;
7. every declared task is run by a surface;
8. the tasks of a profile are partitioned by `reported` / `deferred` /
   `conditional` — every task in exactly one list;
9. a profile declares a `tier`, and it is one of `jvm`, `android`, `maestro`,
   `all`;
10. a profile declares non-empty `reports` globs;
11. no workflow passes a hand-written `--expect` — the expectations live in
    the profile.

**`all` is a declared set, never a wildcard sweep of `build/`.** A report of
a task nobody ran in this run is evidence about a different execution.

## Rationale

**A separate file, not a widened populations file.** The populations file is
read by the build (`-Ptest.population`) and by the tag-coverage test. Adding
task lists to it would make every tag reader parse a structure it does not
need, and would put two different kinds of entity behind one key.

**Reference, not duplication.** If `fast` restated the tag set, the tag set
would live in two places and one of them would be wrong within a week. The
profile names the population; the populations file stays the single owner of
the tags.

**`fast` and `full` differ only by population.** That is a real constraint, not
a coincidence of today's files: if the two profiles ran different task lists,
then the difference between a pull request and a nightly run would not be
described by the population name, and the coverage floors measured on one
would be compared against the other. Rule 4 makes that impossible to write.

**A gate, because the file is only useful if it is true.** The rules above are
all drift-shaped: a new module starts running tests and is not added to `all`
(rule 6), a task is renamed and one profile keeps the old name (rule 2), the
union quietly loses a member (rule 3). The gate is the cheap place to catch
all of them, and it is a static script — seconds, not a JVM run.

**The pseudo-module is declared, not inferred.** `maestro:flows` is not a
Gradle task path; the Maestro tier is a set of YAML flows. Giving it a
declared pseudo-module keeps rule 2's shape ("the module exists") honest
instead of carving out an exception for it.

**Three lists, not one.** A single list of expected tasks cannot say "this
task ran and wrote nothing", and the workflows papered over that with their
own list — which is how two definitions of "what `fast` means" came to
exist, one in the registry and one in each workflow. The three honest answers
are now three lists, and they are checked for completeness, so a task cannot
fall out of all of them.

**The tier is a fact, so it is validated.** `tier` was parsed and never
checked; a profile that said `tier: jvm` while its tasks were the
instrumented ones would have passed. The gate now says which tiers exist.

## Consequences

- "The fast tier" and "the full tier" now have names, and a report can say
  which one produced it.
- The workflow, the coverage floors and the docs stop restating the task list
  and start naming a profile.
- The workflows stop carrying a second list of expected tasks: they pass
  `--profile` and the profile answers both "which reports to look for" and
  "which tasks owe a report".
- A new test module has to be added to `config/test-profiles.properties` and
  to `all` — otherwise `scripts/check-test-profiles.py` fails. That is the
  intended friction: the cost is one line, the benefit is that the list is
  never wrong.
- The gate is advisory-blocking like the other static gates: it is registered
  in the gate registry and runs in the `Static gates` job.
- **Rollback**: delete the file, the reader functions and the gate. The
  populations file and the tag machinery are untouched by this decision.

## Links

- `config/test-profiles.properties` — the profiles
- `config/test-populations.properties` — the populations the profiles reference
- `scripts/src/workflow_gradle.py` — `load_profiles`, `profile_tasks`,
  `profile_population`, `profile_reported`, `profile_deferred`,
  `profile_conditional`, `profile_globs`, `profile_tier`, `is_report_task`
- `scripts/check-test-profiles.py` — the drift gate
- `scripts/tests/test_check_test_profiles.py` — the gate's own tests
- `docs/decisions/2026-11-09-the-evidence-of-a-run-is-indexed-by-one-reporter.md`
  — the reporter that reads this file
- `docs/architecture/tests.md` — «Профили прогона»
