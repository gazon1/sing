---
title: Text shape or fact — auditing every test gate for the input that passes silently
date: 2026-10-05
status: accepted
tags: [gates, testing, testing-integrity, maestro, infra]
---

## Context

The plan `Ремонт измеримости и сценарии покрытия` (0A.5) asks for an audit of
every gate that decides whether a test run counts as evidence, against two
questions, and explicitly asks for **a table rather than fixes** — repairs land
as their own PRs so a gate repair never shares a diff with a behaviour change.

The two questions come from a shape that had already produced three separate
defects. Call it **pattern A: a gate checks the shape of text rather than the
fact that something ran.**

1. `TestTagCoverageTest` defined "has a test" as `startsWith("@Test")`. The two
   recurrence classes are written on `@ParameterizedTest`, so the gate saw no
   test in them and reported both clean — while CI, running
   `-Ptest.tags=fast,slow`, skipped both. The check was "this class has no
   tests" wearing the costume of "this class has no tag".
2. `infra/kiwi/sync.py` had the correct predicate all along. Two
   implementations of one concept had drifted, and the wrong one was the gate.
3. `check-test-runs.py` compares a run against a number recorded earlier, so it
   is structurally blind to a class added *after* the baseline was written. A
   new class can be skipped entirely and the floor still holds.

Pattern A is dangerous out of proportion to its size, because a gate that
matches nothing is indistinguishable from a gate that is working. Both the
Kotlin and the Python side then read as authoritative.

## Idea

For each gate, answer with a **reproducible input** rather than a category —
"a tag with one trailing space", not "bad formatting" — and record whether a
synthetic fixture exists that must fail. Then fix only the rows this change
already touched, and leave the rest as named work.

## Decision

The audit is a table. The rows it closed are closed by the repairs in this
change, each with a fixture that was verified to fail beforehand.

Four of the five repairs are the same move, and it is worth naming it because
the next gate will need it too: **move the check from a judgement about source
text to a fact about an artifact — or give the text check a test of its own.**

- The runnable-test predicate became a data file,
  `config/test-fixtures/runnable-test-members.txt`, read by both language
  implementations. Two implementations in two languages cannot be compared by a
  test written in one of them, so the contract has to be bytes both read.
- The by-results half of `check-test-runs.py` asks whether each declared
  `@Tag("fast")` class produced a JUnit report. That is a fact about the run,
  and it survives a JUnit annotation form no text predicate knows about.
- The suite-name normalisation exists because the first version of that check
  reported all 30 desktopApp classes as missing: `shared` writes `FooTest[jvm]`,
  the other two write the FQN. A check that always fails is a check nobody
  runs, and this one was only caught because the tree was genuinely green.
- `flow_has_tag` could not become a fact — it is a filter over YAML — so it got
  the other half of the treatment: the matching moved into
  `scripts/maestro-flow-tags.sh`, a sourceable file with no adb dependency, and
  `scripts/tests/test_maestro_flow_tags.py` exercises it by *sourcing* the real
  script rather than re-implementing the match in Python. A second copy of that
  predicate would be the drift this repository has already paid for twice.

`fast` is the scope for the by-results check, and it is a correctness argument
rather than convenience. **Corrected 2026-10-05:** this paragraph previously said
the absent-`-Ptest.tags` default "executes exactly the `fast` classes". It does
not. `shared/build.gradle.kts:290-296` maps an absent property to
`excludeTags("slow")`, which runs the `fast` classes **and every untagged class
in the same source set**. CI's `-Ptest.tags=fast,slow` is therefore not a
superset of the local run — it is a *different* selection, and it is strictly
narrower in one direction: it excludes untagged classes, which
`TestTagCoverageTest` is what keeps at zero. Measured 2026-10-05: **0** test
classes without a tag across `commonTest` and `jvmTest`, counting the
fully-qualified `@org.junit.jupiter.api.Tag("fast")` form as well as `@Tag`.

The argument for scoping the check to `fast` survives the correction, and for a
sharper reason than the one originally given. CI always passes an explicit tag
list, so the question that matters there is "did a declared `fast` class run" —
and `TestTagCoverageTest` separately guarantees the untagged population is
empty, which is what makes the `fast` set the whole of what CI should have run.
Checking `slow` as well would fail every local run, because the default excludes
it by design.

The correction is recorded because the wrong version is more flattering: it
claimed a local run and a CI run select the same things, which would have made
every future reasoning about the two configurations unsound.

## Rationale

**Why the fixture table and not an AST walk.** `commonTest` has no parser
dependency, and a gate that needs a new build dependency is a gate that gets
removed the first time that dependency is inconvenient. The regex's blind spot
is bounded and now covered from above: a form it does not know is still caught
by the by-results check, which never parses anything. AST migration is a
separate piece of work with a spike on `commonTest` first.

**Why the shared predicate is one object plus one file, not one shared
constant.** Kotlin cannot import a Python constant, and duplicating the set is
what caused the drift. The table is the only shape both languages can read.

**Why the `flow_has_tag` deferral was the wrong call, recorded because the
reasoning error is the reusable part.** It was filed on the grounds that the
script "could not be exercised in this environment (no device)". But the defect
was in *pure text matching*, and pure text matching is exactly what a unit test
exercises without a device. The constraint was real and the conclusion drawn
from it was not: the untestable thing was the Maestro invocation, not the tag
comparison. Writing the sixteen cases took about as long as the probe that
found the bug, and they immediately surfaced three further defects — an
unterminated tag block that would have made `TAGS=tapOn:` select every flow, a
`tags:` line with a trailing comment that opened nothing, and a CRLF flow whose
tag matched nothing. The general rule: before deferring a fix because "I cannot
run it", check whether the defect is in the part that actually needs running.

## Consequences

- `docs/decisions/deferred-backlog.md` gains the `flow_has_tag` entry, since
  filed and since closed (#148).
- The predicate question has three implementations in this repo: the Kotlin
  `RunnableTestMember`, the Python `_TEST_MEMBER`, and the by-results check,
  which parses nothing. The first two are bound by the fixture table. Adding a
  third *text* implementation is now a review finding.
- **Checked against the traceability machinery, not assumed.** The plan this
  audit belongs to flagged one point where the two efforts meet: the definition
  of a runnable test, which had to be a single implementation rather than two
  that drift a third time. When that plan landed (`ccdabe18`,
  `infra/kiwi/traceability/`), the claim was verified rather than trusted:
  `junit_xml.py` reads the report and never classifies source, and `links.py`
  locates a carrier by a spec-id prefix token and a `@DisplayName` — a different
  question from "does this class contain a member JUnit executes". The
  runnable-test predicate therefore still exists exactly once in text, in
  `sync.py`, and the fixture table binds it on both language sides. The Kotlin
  set in `RunnableTestMember` and the Python set in `sync.py` remain the only
  two spellings of it.
- `check-test-runs.py` depends on `infra/kiwi/sync.py` loading, and that
  dependency is a trap of its own. The first version returned `None` on an
  import failure and the caller skipped the source set — so appending one failing
  `import` line to `sync.py` made the gate print "Test run floors met" and exit 0
  with the by-results half not running at all. It now raises `ScannerUnavailable`
  and the gate fails loudly. This is recorded because the fix introduced the
  vacuous green it then had to remove: a check that cannot run must not report
  success, and the cheapest way to get that wrong is to model "unavailable" as
  "nothing to check".
- The ratchet-baseline idea from the parent plan (a baseline of known holes that
  can only shrink) is deliberately **not** applied to this gate. The measured
  state is zero missing classes across all three source sets, so a baseline would
  start empty and could only ever be regenerated downward — which is the
  "regenerated on failure until it means nothing" failure the Kiwi ceiling ADR
  warns about.
- Measured and deliberately **not** fixed: brace counting over a class body is
  fooled by an unbalanced `{` inside a string literal, and 97 lines in the
  current test tree contain one. Comparing naive brace counting against a
  string-aware version over all 269 real classes produced **zero** differing
  verdicts, so this is latent, not live — and the by-results check covers the
  consequence anyway, since it reads the run rather than the source. Worth
  revisiting only if a test file ever puts a bare `}` in a literal.
- `--update-baseline` used to emit only the source sets it had observed, so
  running it on a machine without a device **deleted** the
  `shared:testAndroidHostTest` floor. That is the same failure this file's own
  header documents: a baseline number that was never a measurement of a real run.
  Unmeasured lines are now carried over, and a rise in either the class or the
  test count is reported on stderr with a reminder that the floor must come from
  the default run rather than `-Ptest.tags=fast,slow`. Both were found by running
  the command, not by reading it.

## Links

- `config/test-fixtures/runnable-test-members.txt` — the shared contract
- `scripts/maestro-flow-tags.sh` + `scripts/tests/test_maestro_flow_tags.py` —
  the tag filter, extracted so it is testable without a device (#148)
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestMember.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestFixtureTest.kt`
- `scripts/tests/test_runnable_test_members.py`
- `scripts/check-test-runs.py` — by-results section
- `openspec/specs/test-execution-integrity/spec.md` — the normative spec
- `docs/decisions/2026-10-05-kiwi-gates-and-rotation.md` — the same argument for
  a ceiling: a floor that regenerates on failure is not a gate
