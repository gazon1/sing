---
title: "An unannotated test member is not a test"
date: 2026-10-07
tags: [testing, architecture, tooling]
status: accepted
---

## Context

PR #223 removed two dead functions from `SingularityCatalogTest`:
`aDueDateWithNeitherPathNorValueIsRejected` and `componentNamesAreUnique`. Both were complete —
they parsed a component, asserted on the outcome, carried explanatory comments — and neither had a
`@Test`. JUnit skipped them silently, the class reported 8 tests where its author had written 10,
and the suite was green.

Nothing in this repository could see that. `TestTagCoverageTest` asks whether a class carries a
`@Tag`; that class did, and it did run. `check-test-runs.py` compares counts against a floor; a
floor moves when the count drops, so it cannot see a member that was never selected. Neither gate
is wrong about its own question — the defect lives in the gap between them: a function shaped like a
check, in a class that is selected, executing nothing.

## Idea

1. **Rely on review.** The two functions were obvious once found. Nothing prevents the third pair.
2. **Require `@Test` on every member of a `*Test` class.** Rejected while measuring: 32 of the
   members it would flag are `@BeforeEach` / `@AfterEach` callbacks. A rule whose first run produces
   32 false positives gets suppressed, and a suppressed gate is indistinguishable from a missing one.
3. **Ask whether the member carries *any* annotation.** `private` helpers are out of scope; anything
   else must state its intent. Lifecycle callbacks now pass because they state theirs.
4. **Scan the text with a regex**, in the style of `RunnableTestMember`.

## Decision

`UnannotatedTestMemberTest` (shared/src/jvmTest/kotlin/com/singularity/todo/arch) enforces rule 3
over every Kotlin tree that holds tests, using Konsist rather than a text scan.

The rule: inside a class whose name ends in `Test`, a member `fun` must be `private`, `abstract`,
`override`, or carry at least one annotation. An `internal` helper is a finding, because no
annotation means "helper" — the two honest remedies are `private`, or moving it to a file not named
`*Test.kt`. Measured on 2026-10-07 over 331 test files across six trees, that closes the gap with
zero findings and no exceptions to carve out.

## Rationale

**Konsist, not a regex — because the regex version failed its own control.** The first
implementation was a brace-counting line scanner, and it was run against the pre-#223 file as a
positive control before being run against the repository. That file contains two unannotated
members. The scanner reported one.

The second was inside a class body whose brace count had been shifted by a multi-line raw-string
JSON fixture: the body appeared to end four lines early and the member after it was never examined.
An earlier draft of the scanner also compared a member's nesting depth against `0` when a member's
depth inside its own class is `1`, which made it report nothing at all — the gate would have been
green while detecting exactly nothing.

This is the second time this repository has paid for that specific scanner. `ClassBodyScannerAgreementTest`
already measures 97 test-tree lines carrying an unbalanced brace inside a literal and establishes
that the naive and string-aware scanners happen to agree today. Here they did not agree, and the
disagreement was silent. Konsist was already a test dependency (0.17.3, used by `DiFacadeTest` and
`ArchitectureTest`), so an AST was available at no new cost and with none of the blind spots.

**A committed broken fixture, because a gate that has never failed is not known to work.** The
control reads `shared/src/jvmTest/fixtures/arch/UnannotatedMemberFixtureSource.kt`, a class that is
wrong on purpose. Reconstructing the sample from git history at review time is not equivalent: the
blindness was in the detector, and a history-derived control would have reproduced it. The fixture
is excluded from every compiler and detekt source path, and its file name deliberately does not end
in `Test.kt` so that `ClassBodyScannerAgreementTest` — which walks all of `src/jvmTest`, not only its
`kotlin` subdirectory — does not count it.

**The `abstract` / `override` exemption came from the rule being wrong, not the tree.** The first
Konsist run reported four members, and all four are the contract-test seam of
`TaskRepositoryContractTest`: `protected abstract suspend fun newRepository(userId)` in the base,
`override suspend fun newRepository` in its fake subclass and in its Room subclass. They are
supertype shapes, not checks, and a bodyless `abstract` member could not be a test whatever it was
annotated with. The fourth was `override fun onIntent` inside the `MviViewModel` builder lambda in
`MviViewModelTest` — an object expression is a declaration rather than a nesting level, so
`functions(includeNested = false, includeLocal = false)` does not exclude it. An AST found these
four; the brace-counting prototype found none of them, because it only counted declarations that
begin with `fun` at a fixed indentation.

**Two more trees than `TestTagCoverageTest` walks.** `androidApp/src/androidTest` holds
instrumented classes that neither CI nor the tag gate selects, which is where an unannotated test
accumulates unnoticed: a class nothing selects is a class nothing checks.
`shared/src/androidHostTest` was added and then removed — it contains an `AndroidManifest.xml` and
no Kotlin, so listing it would make this gate fail on a path that does not exist, which is the same
failure as a vacuous pass wearing a finding's clothes.

## Consequences

- Writing a test now fails the build if the annotation is forgotten. That is the intended cost:
  the alternative was a check that does not run.
- An unannotated `internal` helper inside a `*Test` class is a red gate with no annotation that
  would satisfy it. The remedy is `private`, or a move into a non-test file — which is where fakes
  and helpers already live by convention (`shared/src/commonTest/.../test/fakes/`).
- `functions(includeNested = false, includeLocal = false)` is load-bearing. The Konsist default is
  `true` for both, which would walk into every lambda and report every local helper in the tree.
  It is not sufficient for anonymous objects, which is why the `override` exemption is load-bearing
  too rather than cosmetic.
- The gate covers direct members only. A dead test hidden in a companion object or a nested class is
  outside its reach; extending it means widening the traversal, not relaxing the rule.
- `src/jvmTest/fixtures` is declared as an input of `:shared:jvmTest`, or the positive control would
  report a verdict about a fixture it had never re-read.
- The first version of the gate asserted only "annotated or private" and produced 32 false
  positives on lifecycle callbacks before it was ever run. A gate is measured by what it reports on
  the tree as it stands, not by what it is supposed to report.
- The fixture's own KDoc was the repository's first NEW dead reference. It explained the constraint
  "this file must not be named like a test" by writing that suffix in backticks, and
  `check-doc-dead-refs.py` read the backticked text as a path, found nothing, and failed. The
  constraint was written a paragraph above the prose that broke it, so it was found by running the
  gate rather than by reading the file. A prose claim about a gate is not a measurement of that
  gate — this session produced three of them (the rule itself, the tree list, and this), and the
  gate run is what corrected all three.

## Links

- PR #223 — the two dead members this gate was written for
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/UnannotatedTestMemberTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ClassBodyScannerAgreementTest.kt` —
  the earlier, related measurement of the same class of scanner defect
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TestTagCoverageTest.kt` — the adjacent gate,
  which asks whether a class carries a tag and so cannot see a member that was never selected