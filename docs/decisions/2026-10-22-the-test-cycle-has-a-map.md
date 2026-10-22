---
title: "The test cycle has a map, a quick task, and a vocabulary for the slow half"
date: 2026-10-22
status: accepted
tags: [testing, tools, gates]
---

# The test cycle has a map, a quick task, and a vocabulary for the slow half

## Context

The local verification loop costs about thirteen minutes, and `:shared:jvmTest` is
most of it. A single test — the source scan that reads the whole tree — took
896 seconds of a twenty-five-minute run: fifteen minutes to answer one question
about one rule.

That number is not the whole story. The story is that a developer has no way to
answer "what does this change require?" faster than "everything". So the loop
becomes a thing to postpone rather than a thing to run, and the suite — the only
thing that says the change did not break anything — is the thing that gets
skipped.

`changed-modules.py` already answers a coarser version of the question: *which
module's checks have to run*. It answers it from the module graph the build
files declare, so the answer is computed rather than guessed. But the answer is
a list of Gradle tasks, and the biggest of them is still the whole tree.

Three things are missing, and each of them is a different kind of answer:

1. **Which test classes can this change break?** A class-level answer, not a
   module-level one.
2. **How do I run them?** An answer that ends in a command, not in a list the
   developer has to turn into one.
3. **Which part of the slow half do I need?** The slow half is a hundred
   classes; it is one class when the change is a migration and another when the
   change is a source scan.

## Idea

- A **map** from the changed files to the test classes that can break, computed
  from the same tree the tests read.
- A **quick task** that runs what the map says.
- A **vocabulary of sub-tags** for the slow half, so that `-Ptest.tags` can name
  a category instead of "everything that is slow".

## Decision

**The map is computed, and it says why.**

`scripts/test-impact-map.py` reads the changed files — committed and
uncommitted alike — and names the test classes that can break because of them:

- a change in a scope maps the tests of that scope: the feature's own tests are
  the ones that know its rules;
- a change in a class maps every test that *mentions* that class, wherever it
  lives: a mention is a coupling, and a coupling is a way to break;
- a changed test maps itself: it may now say something different about the code
  it checks;
- a change in the gates maps no app test at all — the gate suite is what has to
  run, and the app's tests do not read the gates.

Every entry carries its reason, and `--explain` prints them. The map is a
**candidate set**, not a verdict: it never says that a test outside the map is
safe, only which ones are worth running first. A map that claimed to be
complete would be a lie the day a rule is not name-shaped.

**The quick task is the map, run.**

`just ti` runs `scripts/test-impact-map.py --run`, which prints the Gradle
command it built and runs it: the tasks of the modules that own the mapped
classes, the tag selection that includes the slow half, and a `--tests` filter
per class.

**The slow half has sub-tags, and the primary tag never moves.**

The vocabulary is `slow:migration`, `slow:room`, `slow:konsist`, `slow:io` and
`slow:compose`. A class carries its primary tag — `fast` or `slow` — and, when
it is slow, a sub-tag naming what makes it slow.

The primary tag is what CI selects on, and it is what the gate checks: a class
that carries only a sub-tag would be absent from every CI run while the gate
that exists to prevent exactly that stays green. So the rule is: the primary
tag is one of `fast`/`slow`, and a sub-tag is from the vocabulary — never
instead of the primary tag.

## Rationale

**Why the map is by scope and by mention, and not by dependency analysis.** The
build knows which module compiles against which, and `changed-modules.py`
already uses that. A class-level dependency graph would need the compiler's
output, and the compiler's output is not in the tree — the tests would have to
read a build artifact, and the map would stop working in a clean checkout. The
two rules the map does use are the two that hold without a compiler: a scope
owns its tests, and a mention is a coupling.

**Why the map is a candidate set.** The dangerous failure is the narrow one: the
map says "nothing to run", the developer believes it, and the change breaks
something nobody looked at. So the map never says "safe" — it says "these
first". A wide map costs a minute; a narrow one costs a bug.

**Why the sub-tags are a vocabulary and not free text.** A free-text tag is a
typo waiting to happen, and a typo in a tag is invisible: the class is absent
from every run and every gate that checks the tag still passes. The vocabulary
is checked by the same gate that checks the primary tag.

**Why the map lives in `scripts/` and not in Gradle.** The map is a question
about the tree, and the tree is what the script reads. A Gradle task would have
to shell out to the same answer anyway, and would add a configuration-cache
dependency to a script that needs none.

## Consequences

- `just ti` is the answer to "what does this change require?", and it is
  computed from the same tree the tests read.
- The map has its own tests, in the directory that holds the gate scripts' own
  tests, so a rule that stops matching is caught by the suite that already
  guards the other rules.
- The slow half can be run by category: `-Ptest.tags=slow:migration` runs the
  migrations and nothing else.
- The map is not a gate. It is a tool: a gate that failed a change for having
  an incomplete map would be a gate about the map, not about the code.

## Links

- `scripts/test-impact-map.py` — the map.
- `scripts/changed-modules.py` — the coarser answer, at module level.
- `scripts/tests/test_test_impact_map.py` — the map's own tests.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TestTagCoverageTest.kt` —
  the gate that holds the tag vocabulary.
