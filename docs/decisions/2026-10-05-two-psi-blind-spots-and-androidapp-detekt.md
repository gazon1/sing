---
title: Two PSI blind spots, and bringing androidApp under the rules
date: 2026-10-05
status: accepted
tags: [detekt, tooling, tests, android, config]
---

## Context

Follow-up to `2026-10-05-positive-tests-for-every-detekt-rule`, which gave all 17 custom
rules a positive test and found two no-ops. That work left three things open:

1. `MviViewModelExt` reads `prop.typeReference`, which is null under type inference.
2. `NoEmptyOnClickLambdaRule`'s preview-file exemption is unreachable through the test
   harness.
3. `androidApp` runs `detekt-minimal.yml` with `ignoreFailures = true`.

All three are the same species of problem: **a check whose scope is narrower than the
thing it is supposed to cover**, and therefore reports success about less than a reader
assumes.

## Idea

For each: widen the check to its documented intent, and prove the widening with a test
that fails against the old behaviour.

The temptation in all three cases is to instead adjust the *expectation* — declare the
narrow behaviour correct and move on. That was rejected in the previous ADR for
`NoDirectDispatchers`, and it is rejected here for the same reason: a gate that covers
less than it claims is indistinguishable, to everyone except whoever writes the gate, from
one that covers what it should.

## Decision

**1. `MviViewModelExt` also reads the initialiser.**

`private val s = MutableStateFlow(x)` has a null `typeReference`, so it escaped the rule
entirely. The rule therefore depended on whether the author happened to write a type
annotation — unrelated to the thing being policed. It now checks `prop.initializer?.text`
as well, which needs no type resolution. Declared-type checking is kept, because an
initialiser-only check would flag `val s: State<UiState> = MutableStateFlow(...)`.

**2. `NoEmptyOnClickLambdaPolicy` extracted.**

`compileContentForTest` derives the file name from the package argument, so no fixture can
produce a `*Preview*` file and the exemption was structurally untestable. The file
previously said so in a comment, next to a test asserting `"taskrowpreview".contains(
"preview")` — true regardless of the rule, so it proved nothing. That test is gone; four
real ones replace it, including the repo's actual convention (`core/ui/preview/` exists,
`Preview.kt` does not).

**3. `androidApp` uses the shared config, with the custom rules on its classpath, and
`ignoreFailures = false`.**

Reaching 0 findings needed three things beyond swapping the config file:

- androidApp had neither `:detekt-rules` nor `detekt-formatting` on its detekt
  classpath, so the shared config's custom rule-set and `ktlint:` sections were both
  unresolvable there. The module had never been in a position to run the project's rules.
- **`androidTest` appeared in no test-source exclude list in detekt.yml.** `FunctionNaming`
  and six other rules exclude `**/test/**`, `**/commonTest/**`, `**/jvmTest/**` and
  `**/androidHostTest/**`; androidApp's instrumented tests were the one test source set
  nobody had enumerated, so their backticked names were reported as violations. Added to
  all seven lists.
- `source.setFrom` listed `src/androidAndroidTest/kotlin`, which does not exist.

**`src/debug` is deliberately still unlinted.** Wiring it surfaces 16 findings, all in
`DebugSeedActivity.kt`, and the substantive ones are the interesting case: `runBlocking`,
`Clock.System` and `TooGenericExceptionCaught` are correct for production and wrong for a
one-shot debug seeder that blocks a background thread and must not crash the app. Each
would need a suppression. Whether debug-only tooling should be governed at all is a
policy question that generalises to every future `src/debug` file, so it is recorded in
`deferred-backlog.md` as `androidapp-debug-source-set-unlinted` and left to whoever owns
the debug tooling.

## Rationale

Two of these were only visible once a check existed to make them visible, which is the
point. The `androidTest` gap had survived every prior pass over `detekt.yml` — including
this day's — because nothing enumerated the test source sets against the rules that
reference them. It surfaced only when a module that *has* instrumented tests was finally
held to the shared config.

`src/debug` is the deliberate exception, and the distinction matters. The other three
changes widen a check to its already-documented intent; the fourth would be deciding a
policy and encoding it. Baselining all 16 without deciding would have been the
easy-looking option and would have quietly answered "debug code is governed like
production" as a side effect of not wanting a red build.

A related note on the CI state at the time of writing: `main` is red. `RoomTaskRepository-
ContractTest` fails on the GitHub runner with `IllegalArgumentException` on all 15 of its
tests, and does not reproduce locally. That is a pre-existing failure on the
desktop-test-diagnostics merge, not a consequence of this work — but it is the same
failure mode this sweep keeps finding: a required check that is permanently red teaches
people to ignore required checks.

## Consequences

- All three detekt modules report **0 findings**: `:detekt-rules`, `:androidApp` and
  `:shared`. `:detekt-rules:test` is at 107 tests, 0 failures, 91.6% line coverage.
- The 17 custom rules now apply to `androidApp` — including `ProhibitUserIdInObserve`,
  which guards cross-profile reads, and `PassThroughUseCase`, which the previous config
  could never have run there.
- `detekt.yml`'s test-source excludes now cover all five KMP/JVM test source sets. Any
  future one added must go into all seven lists, or the module using it will report its
  test names as violations.
- `src/debug` remains unlinted, recorded, and owned by a decision someone still has to
  make. The first attempt was four suppressions plus twelve formatting fixes; the
  backlog entry says what the cheapest correct policy is.
- `RoomTaskRepositoryContractTest` needs its own issue. A permanently red required check
  is indistinguishable from no check, and it is currently hiding the real state of `main`.

## Links

- `2026-10-05-no-direct-dispatchers-rule-was-a-no-op` — the first no-op; the shared shape
- `2026-10-05-positive-tests-for-every-detekt-rule` — the audit that found the second
  no-op and the coverage measurement that located these gaps
- `2026-10-05-doc-gates-must-parse-structure` — the same class of problem in the doc gates
- `docs/decisions/deferred-backlog.md` — `androidapp-debug-source-set-unlinted`,
  `detekt-rules-with-psi-blind-spots` (now partly closed),
  `detekt-rule-branch-coverage-owed`
- `singularity-todo-detekt-rules-authoring` — the `compileContentForTest` traps and the
  policy-extraction pattern
