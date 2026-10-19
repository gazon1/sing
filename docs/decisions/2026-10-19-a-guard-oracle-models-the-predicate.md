---
title: "A guard's oracle must model the predicate it guards, not a neighbouring one"
date: 2026-10-19
status: accepted
tags: ["testing", "gates", "contract"]
---

## Context

`TestTagsCatalogJvmTest` has a test called "dynamic functions count matches raw
fun scan — guards against line-wrap dropping functions". It compares the
catalogue's count of dynamic tag functions against a raw scan of `TestTags.kt`,
and it had been failing on a clean checkout:

```
TestTagsCatalog returned 33 dynamic functions but raw scan found 36 fun declarations
— a line-wrap may have caused the parser to skip a function
```

The message blamed a line-wrap. The line-wrap was not the cause.

The catalogue declares **tag functions**: functions whose value is built from a
`slug`, so that a runtime label can be produced from a runtime name. Three
functions in the file are tag functions that deliberately do not route through a
slug — `menuItem` (the id *is* the tag), `calendarDay` (it formats an ISO date)
and `calendarWeekdayHeader` (it lowercases a label). The raw scan counted
*every* `fun` in the file. 36 against 33 is exactly those three.

So the test compared two different categories and called the difference a
defect — and its failure message pointed at a cause that had nothing to do with
it. A reader would have gone looking for a line-wrap in `TestTags.kt` and found
nothing.

This is the same shape as the defect the D1 measurement found in
`ClassBodyScannerAgreementTest` (ADR
`2026-10-19-kotlin-text-scanning-one-contract.md`): a guard whose oracle shared
a blind spot, or measured a different thing than the thing it guarded. There it
was a blind spot; here it was a wrong category.

## Idea

Make the oracle measure the same thing the guard measures, and say out loud
which thing that is.

## Decision

**The oracle counts the category the catalogue declares, and the test measures
two independent things about it.**

- The oracle counts the `slug` templates in the file — how many dynamic tag
  functions the source declares — and compares that with the catalogue's size.
  A dropped or invented declaration changes the count.
- The oracle also derives the *set of function names* that carry a template,
  by slicing the file into declarations (a `fun` line up to the next `fun`
  line, so a declaration wrapped over several lines is read whole), and
  compares it with the catalogue's names. A declaration attributed to the wrong
  owner changes the set, and the count alone would not notice.
- A guard against a vacuous pass: the test asserts that more than twenty
  templates were found, so an oracle that read nothing would fail rather than
  agree.

**The catalogue was not changed.** It is right: it declares tag functions, and
three of this file's functions are not tag functions of that shape.

## Rationale

- **A guard that measures a different quantity reports defects that are not
  there.** The cost is not the red test; it is that a red test teaches people
  to ignore the guard, and then the guard stops being the reason the next real
  defect is caught.
- **A failure message that names the wrong cause sends the reader the wrong
  way.** "A line-wrap may have caused the parser to skip a function" is a
  hypothesis, and a wrong one is worse than none: the reader checks the
  hypothesis, finds nothing, and concludes the test is flaky.
- **One number fails in one way only.** A count cannot tell a dropped
  declaration from a misattributed one. Two measurements can.
- **The comparison must stay independent of the implementation.** The oracle
  does not use the catalogue's regex; it counts templates and slices
  declarations, which is a different construction over the same bytes.

## Consequences

- **The test is green**, and the difference it reports is now a difference
  between two things that mean the same thing.
- **The catalogue's brace counting was fixed at the same time** — it counted
  braces per line over raw text, so a brace in a string or in a KDoc moved the
  depth and a nested declaration could be attributed to the wrong object. It
  now takes its depth from the shared lexer (ADR
  `2026-10-19-one-kotlin-lexer.md`).
- **The rule generalises.** When a guard is red, the first question is not
  "which side is wrong" but "do these two measure the same thing". A guard
  whose oracle measures a neighbouring quantity is not a guard.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/core/ui/TestTagsCatalogJvmTest.kt` — the fixed oracle
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/ui/TestTagsCatalog.kt` — the catalogue
- `docs/decisions/2026-10-19-kotlin-text-scanning-one-contract.md` — the same shape, in the scanner
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the rule this follows
