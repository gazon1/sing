---
title: "ast-grep is not adopted for the Kotlin that Python gates parse — and the measurement that said so"
date: 2026-10-19
status: accepted
tags: ["tooling", "gates", "kotlin", "ast-grep", "measurement"]
---

## Context

The source was a post arguing that `ast-grep` makes code checks precise where regular
expressions guess. The plan built on it had three tracks: a Gradle plugin for
dependency analysis, `lychee` for documentation links, and `ast-grep` for the Python
gates that parse Kotlin.

Two of the three tracks did not survive contact with the tree — the plugin
(ADR `2026-10-19-dependency-analysis-plugin-rejected.md`) and, as this record
documents, `ast-grep` itself. The premise needed checking first: this repository
already lints Kotlin with detekt 2.0 (Kotlin PSI, 24 custom rules) and Konsist
0.17.3, both of which work on the compiler's own syntax tree. A tree-sitter-based
tool cannot beat a PSI-based one at Kotlin linting. The only niche left was
**Python gates that parse Kotlin with regular expressions**.

The candidate primitives, and the regular expressions each one rests on:

| Primitive | Script | Regular expression |
|---|---|---|
| P1 test-class discovery | `scripts/src/scanners/test_classes.py` | header match + brace counting |
| P2 qualified references | `scripts/check-dependency-usage.py:83` | `\b([a-z][\w]*(?:\.[a-zA-Z_]\w*){2,})\b` |
| P3 log interpolation | `scripts/check-log-interpolation-allowlist.py:87-91` | `log\.\w+\s*\{\s*"` per line, `\$\{([^}]+)\}` |
| P4 unwired composables | `scripts/find-unwired-surfaces.py:287` | `^fun ([A-Z]\w*(?:Screen\|Card\|…))\s*\(` |

The repository's own precedent argues against a parser here. `RunnableTestMember`
in `shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestMember.kt`
says: *"The alternative is an AST walk, which is the more correct tool. It is not
used here because `commonTest` has no parser dependency today, and a gate that
needs a new build dependency is a gate that gets removed the first time that
dependency is inconvenient."* That reasoning was recorded without a benchmark, so
the benchmark was run.

## Idea

Measure both implementations of each primitive on the real tree and compare
**verdicts**, not counts. A gate answers a question; a different implementation
that answers it the same way is not an improvement, and a different implementation
that answers it differently is a change to what the gate means. Only a primitive
where the AST answers *better* — more findings that are true, or fewer false ones —
is a candidate for migration.

`ast-grep` 0.51.0 (the Rust CLI) was used directly, with `kind:` rules over
`shared/src`, `androidApp/src`, `desktopApp/src` and the other production roots.

## Decision

**`ast-grep` is not adopted. No primitive is migrated.** The measurement, on
1646 Kotlin files:

| Primitive | Regular expression | AST | Verdict change | Outcome |
|---|---|---|---|---|
| P1 test classes | 425 classes | 415 | 14 lost by AST, 2 gained | AST is **worse** |
| P2 dependency references | 1700 prefixes | 1612 | **none** — 11 findings either way | no change |
| P3 log interpolation | 28 interpolations | 74 | **none** — 0 findings either way | no change |
| P4 unwired composables | 113 candidates | 198 | **none** — same findings | no change |

The measurements in detail:

- **P2 — identical verdicts.** Both semantics produce exactly the same 11 findings,
  and all 11 match the 11-entry allowlist. The AST sees 89 prefixes the regular
  expression does not; 45 of those exist only inside string literals, which is the
  regular expression's real blind spot (a package named in a log message counts as
  a use). Closing it changes nothing on this tree: no declared dependency's package
  root is among the 89.
- **P4 — identical verdicts, and the extra candidates are all wired.** The regular
  expression requires the declaration to start at column 0 with `fun`, so it misses
  every composable declared `private` or behind an annotation: 113 of 198. All 85
  extra candidates are referenced from production code, and none is called only
  from a `@Preview`. The gate's findings are the same under both.
- **P3 — identical verdicts.** Every one of the 28 interpolations the gate sees is
  confirmed by the AST at the same line, with the same base type. The AST also
  sees 46 the gate cannot (multi-line log calls, `log.e(e) { … }`), and none of
  them changes the verdict — but that is a defect in the *log gate*, not an
  argument for the AST (see Consequences).
- **P1 — the AST is worse.** `tree-sitter-kotlin` mis-parses **39 of 1646 files
  (2.4 %)**, and every one of the 14 classes the AST loses is in one of them. The
  trigger is a construct this repository uses: adjacent raw strings —
  `"""…""" + """…"""` — which the tree-sitter lexer mis-lexes, after which the
  rest of the file is swallowed as a string. The worst case is
  `shared/src/jvmTest/kotlin/com/singularity/todo/arch/DroppedResultIsReportedTest.kt`,
  which declares 18 types and of which the AST sees 4. Ten files contain the
  construct.

A gate built on a parser that silently mis-reads 2.4 % of the tree would report
findings that are not there and miss findings that are — the exact failure mode
this repository's own `A gate that lies is worse than no gate`
(`docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md`) exists to
prevent. The decision is not "the AST is worse at Kotlin"; it is "**this** parser
is not trustworthy on **this** corpus".

## Rationale

Three reasons, in order of weight.

1. **No primitive's verdict changes.** Adopting the AST would add a binary
   dependency, a grammar dependency, and a CI install step to buy nothing
   measurable. The repository's gates are measures, not style checks: a gate that
   says the same thing is not improved by saying it with a different tool.
2. **The parser fails on this corpus.** 2.4 % of files is not a rounding error in
   a corpus of 1646 files that a single unreadable file can break. The failure is
   silent — the AST does not report an error, it reports a different tree.
3. **The regular expression's blind spots are real but narrow, and not what a
   parser fixes.** The measured blind spots are (a) string literals naming a
   package, (b) multi-line log calls, (c) a composable declared with a modifier,
   and (d) a class body whose span is shifted by an unbalanced brace inside a
   string. (a) and (c) change no verdict; (b) and (d) are defects in the
   *detection* layer of two gates, and are fixed by fixing that layer — a
   file-level stripper — not by changing the parser.

The rejected alternatives:

- **Adopt `ast-grep` for the primitives where the verdicts agree anyway.** This
  buys a dependency and a 2.4 %-miscount parser in exchange for nothing.
- **Adopt it only for P1, where the regular expression is measurably wrong.** It
  is measurably wrong in the other direction too: the AST loses 14 real classes.
  The fix that helps is a stripper that understands raw strings and block
  comments, and it is a 60-line change to a file that already has a hand-written
  character walk.
- **Use the AST only to *audit* the regular expressions, keeping the regular
  expression in production.** This is what the measurement just did, by hand, in
  an afternoon. A permanent auditor would be a second implementation of the same
  predicates to maintain — the drift the repository's fixture table
  (`config/test-fixtures/runnable-test-members.txt`) exists to prevent.

## Consequences

- **No CI dependency is added.** `ast-grep` is not installed, not pinned, and not
  downloaded. `/usr/bin/sg` (util-linux's setgid runner) remains a footgun that
  nothing points at.
- **This supersedes the `ast-grep` bullet in
  `docs/decisions/2026-10-13-ensure-device-acquires.md`.** That bullet rejected
  the tool because `static-gates.sh` was "explicitly the no-JVM gate registry",
  which was wrong (the premise correction in that file is dated 2026-10-19), and
  then deferred the question to "a future benchmark". The benchmark is this
  record, and the answer is no.
- **The measurement found three defects the AST would not have fixed.** They are
  reported here so they are not lost, and they are *not* fixed by this decision —
  each changes what a gate reports, which is a spec-level change and the
  maintainers' call, not a consequence of choosing a parser:
  1. **The test-class scanner is blind to a class body shifted by a string.**
     `scripts/src/scanners/test_classes.py:_has_test_member` counts braces to
     find the end of a class body, and its stripper does not understand raw
     strings or block comments. The Kotlin side
     (`RunnableTestMember.classBody`) has the same weakness, and so does the
     oracle in `ClassBodyScannerAgreementTest` — which is why the tripwire can
     never fire for this shape: the oracle shares the implementation's blind
     spot, which the oracle's own KDoc says it must not. Five real test classes
     are invisible to the test-class inventory today
     (`ApplyOutcomeArmsTest`, `SharedHarnessConventionsTest`,
     `TaskTimeSlotFailureTest`, `ArchitectureTest`, `EnqueueSiteIsScannedTest`).
     All five carry a `@Tag`, so no test is being skipped in CI — but the
     tag-enforcement guard has a hole, and a class that *were* untagged would be
     exempt from the requirement and silently excluded by `-Ptest.tags`.
     Measured blast radius of a file-level stripper: 5 classes become visible,
     0 of them untagged. The repository's own fixture table is where the two
     languages' contract for this belongs.
  2. **The log-interpolation gate sees 28 of 74 interpolations.** It scans line
     by line, so a multi-line `log.w {` … and a `log.e(e) { … }` are both
     invisible. Its docstring also promises that a type it cannot resolve "will
     be treated as user-provided and will fail the gate", while the code skips
     unknown types silently — and 38 distinct base types in this tree are
     unknown to its 17-entry resolution map, `email` among them.
  3. **The log-interpolation gate's positive control cannot fail.** Its control
     removes the `TaskId` entry from the allowlist, but no log call in the tree
     interpolates a base type that resolves to it, so the gate still reports
     "0 unexpected" and exits 0. The control is also absent from
     `SCRIPT_GATES` in `scripts/check-gate-wiring.py`, so Part B never executes
     it at all. A control that cannot fail is worse than no control, because it
     is reported as a passing one.
- **The `ast-grep` question is closed for this corpus.** If the repository's
  Kotlin ever stops using adjacent raw strings, the measurement should be redone
  before the conclusion is inherited — the parse-quality number (2.4 %) is the
  one to re-measure, and it is the one that decides the matter.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestMember.kt` —
  the "no parser dependency" precedent this decision upholds
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ClassBodyScannerAgreementTest.kt` —
  the tripwire whose oracle shares the implementation's blind spot
- `scripts/src/scanners/test_classes.py` — the Python-side test-class scanner
- `scripts/check-dependency-usage.py` — P2, the qualified-reference primitive
- `scripts/check-log-interpolation-allowlist.py` — P3, the log-interpolation gate
- `scripts/find-unwired-surfaces.py` — P4, the unwired-surface detector
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the
  rule the parser's silent mis-parse would have broken
- `docs/decisions/2026-10-13-ensure-device-acquires.md` — the bullet this record
  supersedes
- `docs/decisions/2026-10-19-dependency-analysis-plugin-rejected.md` — the other
  track the same measurement effort killed
