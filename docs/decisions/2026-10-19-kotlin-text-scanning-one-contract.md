---
title: "Kotlin source text is scanned by one character-walk implementation, and two languages agree on it through a fixture table"
date: 2026-10-19
status: accepted
tags: ["tooling", "testing", "gates", "kotlin-multiplatform"]
---

## Context

Two scanners in this repository need to know what a *test class body* contains,
and both of them were wrong in the same way.

- `scripts/src/scanners/test_classes.py` (Python) finds test classes and whether
  they contain a runnable test member, and feeds the tag-coverage gate.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestMember.kt`
  (Kotlin) does the same thing for the architecture tests.

Both stripped "strings and comments" with a line-oriented regex. That approach
cannot see a brace that lives inside a string, a raw string, a KDoc block, or a
nested block comment — and a brace that survives the strip changes where the
"class body" ends. The consequence was measured, not guessed: **five** test
classes in the repository were invisible to the scanner. All five carry a
`@Tag`, and CI therefore ran them, while the *tag-coverage guard* — whose whole
job is to see that every test class carries a tag — could not see them. The hole
was in the guard, not in the tests.

The two scanners were also structurally unable to disagree: the Kotlin test that
compares them (`ClassBodyScannerAgreementTest`) used an oracle that shared the
implementation's blind spots, so the agreement test could not fail on the very
defect it existed to catch.

## Idea

Do not add a Kotlin parser. Instead:

1. Write **one** honest character-walk over Kotlin source text that separates
   code from string literals and comments, and share it between the two
   languages through a **fixture table** — a data file that both a Python test
   and a Kotlin test read, so the contract is a data file rather than two
   implementations in two languages that nobody ever compares.
2. Make the agreement test's oracle an **independent** implementation (a state
   machine over transitions rather than a set of string substitutions), so the
   two sides can actually fail apart.
3. Add the cases that broke the scanners as fixture records, so the blindness
   cannot come back silently.

## Decision

**One character-walk implementation per language, one shared fixture table, and
an independent oracle on the Kotlin side.**

- `scripts/src/scanners/source_text.py` — the Python side. `strip_code` and
  `strip_comments` are length- and newline-preserving, so line numbers and
  column offsets of the surrounding code do not move. The walk understands
  plain and raw strings, char literals, escapes, series of four or more
  backticks (the closing run is the last three quotes of the series), **nested**
  block comments, and unterminated literals (which consume the rest of the
  file rather than throwing).
- `shared/src/jvmTest/.../RunnableTestMember.kt` — the Kotlin side. It gained
  `codeLines()` and its own stripper with the same rules.
- `config/test-fixtures/runnable-test-members.txt` — the contract. It is read
  by `scripts/tests/test_runnable_test_members.py` and by
  `RunnableTestFixtureTest.kt`. Four records were added for the cases that
  broke the scanners: a brace inside a plain string, a brace inside a raw
  string, a brace inside a KDoc block, and a nested block comment.
- `ClassBodyScannerAgreementTest` — its oracle was rewritten as an independent
  state machine, and its KDoc was corrected: the previous claim that the defect
  had "zero measured influence" was false, and the measured influence was five
  real classes.

**Both sides were changed.** Fixing only the Python side would have created
precisely the drift the fixture table exists to prevent: two languages, one
predicate, two implementations, no cross-check.

**No parser dependency was added.** The precedent is in the repository:
`RunnableTestMember` deliberately has no parser on its classpath, because
`commonTest` cannot take one. The same constraint applies here.

## Rationale

- **A guard that cannot see the class cannot protect the class.** The tag-coverage
  gate is the thing that makes CI's `-Ptest.tags=fast,slow` filter safe. A
  scanner that is blind to five classes makes that filter unsound in exactly
  the direction that loses test coverage.
- **Two implementations in two languages is a drift generator, not a check.**
  The fixture table is the contract; the languages are allowed to differ in
  implementation, not in verdict. This is the same rule the repository already
  applies to the "one predicate, two languages" problem in the AI-action
  registry.
- **An oracle that shares the implementation's blind spots is not an oracle.**
  The old oracle stripped strings and comments with the same regex family as
  the implementation, so both failed together. An independent formulation —
  a state machine with explicit transitions — is what makes the agreement test
  capable of failing.
- **Nested block comments are a language rule, not an implementation detail.**
  Kotlin nests them; a scanner that does not will mis-slice any file that uses
  them. KDoc, by contrast, cannot itself contain the block-comment markers,
  so the fixture cases describe them in words.
- **A parser would be a large dependency for a small problem, and a wrong one.**
  The measured alternative (`tree-sitter-kotlin`) mis-parses 39 of 1646 files
  in this repository — 2.4 % — on adjacent raw strings. See
  `2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md`.

## Consequences

- **The five previously invisible classes are now visible**, all of them with
  tags: `ApplyOutcomeArmsTest`, `SharedHarnessConventionsTest`,
  `TaskTimeSlotFailureTest`, `ArchitectureTest`, `EnqueueSiteIsScannedTest`.
- **The Python gate suite grew by 17 tests** (1028 tests in
  `scripts/tests`), covering the stripper's edge cases directly.
- **The Kotlin scanner and the Python scanner can now disagree**, and the
  agreement test is capable of reporting it.
- **The fixture table is now load-bearing**: adding a case to it changes what
  both languages must agree on. It has to be edited deliberately.
- **The stripper is not a Kotlin parser.** It is a lexer for the constructs that
  change the meaning of "where does the class body end". Constructs outside
  that set are out of scope, and the file says so.

## Links

- `scripts/src/scanners/source_text.py` — the Python walk
- `scripts/src/scanners/test_classes.py` — the Python scanner
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestMember.kt` — the Kotlin walk
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ClassBodyScannerAgreementTest.kt` — the independent oracle
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TestTagCoverageTest.kt` — the guard that had the hole
- `config/test-fixtures/runnable-test-members.txt` — the cross-language contract
- `docs/decisions/2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md` — why no parser was added
