---
title: "There is one Kotlin lexer, it classifies every character, and the two languages agree through a fixture table"
date: 2026-10-19
status: accepted
tags: ["tooling", "testing", "gates", "kotlin-multiplatform"]
---

## Context

The repository had **five** ways to strip comments from Kotlin source, and each
one was a partial implementation of the same idea:

- `SourceScan.stripComments` — a mode loop over four states. It knew about
  plain strings and line comments, and nothing else: no raw strings, no nested
  block comments, and string contents left visible.
- `CachedIdentityReadArchitectureTest.stripComments` — a regex pair of its own.
- `CrashReportingWiringTest.stripComments` — another regex pair of its own.
- `UiAutomationSelectorTest.stripComments` — a third regex pair of its own.
- `RunnableTestMember.stripCode` — a character walk, added in the D1 work.

`SourceScan` had already said the rule out loud when it was written: "Two
copies of a comment stripper means two sets of bugs in it … Extracted so the
third such rule does not write a third one." A third was written anyway, and
then two more.

The cost was measured, not assumed. Counting braces over raw source — what
every one of those strippers feeds — counts braces that are not braces:

- **444 of 1650** Kotlin files (27 %) get a different brace depth depending on
  whether the literals are visible.
- **119 of 1980** block-bodied functions (27 files) have their body end read
  differently by the old comment-only counting and by a code-only matcher. A
  spot check of one of them: the old end lands *inside a raw string*, in the
  middle of an embedded Kotlin snippet.

Every rule that reads a body inherits the error silently: "does this function
drop a `Result`", "does this class declare a test", "is this declaration
inside this object".

## Idea

One walk, in one place per language, that classifies every character as code,
comment or literal — and every operation built on top of it as a projection of
that classification. The two languages are held to the same answers by a
fixture table, because two implementations in two languages cannot be compared
by a test written in one of them.

## Decision

**`KotlinText` is the Kotlin lexer and `source_text.py` is the Python one.
Both classify code / comment / literal, and both are pinned by a shared
fixture table.**

- `shared/src/jvmTest/.../arch/KotlinText.kt` — the Kotlin walk.
  `stripComments`, `stripCode`, `matchingBrace`, `firstCodeBrace`,
  `blockBody`, `lineDepths`.
- `scripts/src/scanners/source_text.py` — the Python walk, with the same
  operations and the same rules.
- `config/test-fixtures/kotlin-brace-matching.txt` — the cross-language
  contract for "where does a block end", read by
  `KotlinTextFixtureTest.kt` and `scripts/tests/test_kotlin_brace_matching.py`.
  Twelve records, including an `unclosed` one as the positive control.
- `config/test-fixtures/runnable-test-members.txt` — the existing contract for
  the runnable-test predicate, extended with two template records.

**The model is a classification, not a strip.** Every character is *code*,
*comment* or *literal*; a template's body is code and its `${` and `}` are
literal text. The operations follow:

| Operation | Comments | Literal contents | Template body |
|---|---|---|---|
| `stripComments` | blanked | kept | kept |
| `stripCode` | blanked | blanked | kept |
| `matchingBrace` | not counted | not counted | counted |

**A template is code.** `"${run { 1 }}"` holds a brace that closes a real
block. Treating the whole string as text loses two braces and ends the
enclosing block in the wrong place; treating it as code counts the text around
it. The template's *delimiters* are not braces, and counting them is how an
implementation ends a block early.

**The consumers now share one implementation:**

- `RunnableTestMember` delegates to `KotlinText.stripCode`; its own walk is
  gone.
- `SourceScan.bodyOf` matches braces through `KotlinText.matchingBrace`. It
  used to count every brace in the raw text, which is where the 119 mis-scoped
  bodies came from.
- `TestTagsCatalog` takes its per-line depth from `KotlinText.lineDepths`
  instead of counting braces per line over raw text, and takes its text from
  `KotlinText.stripComments` instead of `substringBefore("//")` — which cut a
  line at the first `//` and took a declaration with it when the line held a
  URL.
- The four local stripper copies are deleted; their callers use
  `KotlinText.stripComments`.

**The oracle in `ClassBodyScannerAgreementTest` stays a second implementation,
on purpose.** A guard that compares an implementation with itself cannot fail.
It is now template-aware, so it does not report a difference on every file that
contains a template — a tripwire that cries wolf is a tripwire people switch
off.

## Rationale

- **One implementation, one bug.** The copies did not merely duplicate the
  work; they duplicated the *edge cases*, and each copy had its own set of
  them. `SourceScan` wrote that rule down before the copies arrived.
- **Classification generalises where stripping does not.** "Blank the comments"
  is one operation; "know which characters are code" is a model, and every
  operation the repository needs — comment-only, code-only, brace matching,
  depth per line — is a projection of it. The template case is the proof: a
  stripper has no way to express "the body of this string is code".
- **The measure decides whether the change is worth it.** 119 mis-read
  function bodies in 27 files is not a tidiness argument; it is 119 places
  where a rule about a function's body could be decided by a brace in a
  string.
- **A data file is the only way to hold two languages together.** A test that
  asserts Kotlin == Python cannot be written in either. The fixture table is
  the contract, and both suites read the same bytes.
- **The oracle earns its keep by being different.** Independence is worth
  more than a second implementation of the same idea.

## Consequences

- **Five strippers became one**, and the four local copies are gone.
- **The brace depth is now the same everywhere it is counted.** The catalogue
  attributes nested declarations to the right object, and `SourceScan.bodyOf`
  ends a body where the body ends.
- **The two languages are pinned to each other** by a fixture table, so a
  change to one that the other does not share is a red test rather than a
  review comment.
- **The lexer is a lexer, not a parser.** It knows where the code ends; it
  does not know what the code means. Type resolution is still out of scope, and
  `SourceScan`'s KDoc still says so.
- **The fixture tables are load-bearing.** A new shape — a template, a raw
  string with four quotes — is a record in the table, added on both sides at
  once.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/KotlinText.kt` — the Kotlin lexer
- `scripts/src/scanners/source_text.py` — the Python lexer
- `config/test-fixtures/kotlin-brace-matching.txt` — the cross-language contract
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/KotlinTextFixtureTest.kt` — the Kotlin reader
- `scripts/tests/test_kotlin_brace_matching.py` — the Python reader
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SourceScan.kt` — the plumbing around it
- `docs/decisions/2026-10-19-kotlin-text-scanning-one-contract.md` — the D1 contract this completes
