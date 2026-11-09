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

The properties that exist are `tier` and `population`. `device` and `e2e` have
no population, and that is not an omission: the instrumented tests carry no
`@Tag` at all, and a Maestro flow's tag (`smoke`, `regression`) lives in a
namespace JUnit knows nothing about. A population there would be a name for a
selection nothing implements.

**The reader is `scripts/src/workflow_gradle.py`**, next to `load_populations`:
one module owns the vocabulary of "what a run is". It exposes `load_profiles`,
`profile_tasks`, `profile_population` and `is_report_task`; `is_test_task`
now delegates to it, so the two notions cannot disagree about what a test task
is.

**The gate is `scripts/check-test-profiles.py`.** It holds seven rules, and
each one is a drift that has already happened or is easy to write:

1. the vocabulary is exactly `fast`, `full`, `device`, `e2e`, `all`;
2. a task is `module:task`, and the module exists (or is the declared
   pseudo-module `maestro`);
3. `all` is exactly the union of the others;
4. `fast` and `full` differ only by population;
5. a profile that is not tag-selected declares no population;
6. every test task a surface runs is declared in `all`;
7. every declared task is run by a surface.

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

## Consequences

- "The fast tier" and "the full tier" now have names, and a report can say
  which one produced it.
- The workflow, the coverage floors and the docs stop restating the task list
  and start naming a profile.
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
  `profile_population`, `is_report_task`
- `scripts/check-test-profiles.py` — the drift gate
- `scripts/tests/test_check_test_profiles.py` — the gate's own tests
- `docs/architecture/tests.md` — «Профили прогона»
