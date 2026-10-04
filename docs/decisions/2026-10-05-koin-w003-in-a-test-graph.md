---
title: KOIN-W003 on a test entry point, and a rule in AGENTS.md that overstated its own case
date: 2026-10-05
status: accepted
tags: [di, koin, testing, ci, process]
---

## Context

`KoinGraphValidationTest` reported:

```
w: [Koin][KOIN-W003] Graph not verifiable at compile time: the entry point loading module
is loaded with a dynamically-computed module set (a conditional, spread, or variable), so
the assembled graph is unknowable here.
  at: KoinGraphValidationTest.kt:148
```

The call was `modules(platformModule(), *domainModule().toTypedArray(), gateModule(…))` —
a spread — and `AGENTS.md` states that a spread is what produces KOIN-W003, with list
composition as the fix. So the first move was to apply the documented fix.

## What the documented fix actually did

Nothing, as far as the warning is concerned. Measured, with a forced recompile each time
so the result could not be an incremental-build artefact:

| Shape of the `modules(…)` call | KOIN-W003 |
|---|---|
| `modules(a, *list.toTypedArray())` — spread | yes |
| `modules(listOf(a, b) + list)` — composition | **yes** |
| composition, module as a top-level function | **yes** |
| composition, module as a class member function | **yes** |
| `modules(listOf(gateModule(…)))` — no local platform module | **no** |
| production entry points in `desktopApp/main.kt`, `SingularityApp.kt`, `mcp-server/Main.kt` | **no** |

The variable is `desktopPlatformModule()` — a ~40-binding test-local mirror of
`platformModule()`. Remove it from the module set and the warning disappears; leave it in
and the call shape is irrelevant.

## Decision

Keep the composition. Fix the rule's justification. Do not restructure the mirror.

**The composition stays** because it is what every production entry point uses and because
it keeps the module set a value the reader can see. Two independent reasons, and the
spread violates both: it obscures which modules are loaded, and `toTypedArray()` on a
`List<Module>` throws away the type the compiler can reason about.

**`AGENTS.md` is corrected.** It said the spread "gives KOIN-W003" and offered composition
as the remedy. The spread does give KOIN-W003 — but so does composition, on this entry
point. A rule whose stated reason is "this produces warning X" invites the next reader to
check for warning X, find it still there, and conclude the rule was misapplied. The real
justification is that a module set the compiler cannot see is a module set nobody can
check, and composition is a necessary step toward that rather than a sufficient one.

**The mirror is not restructured.** Converting a 40-binding inline `module { }` into
`@Module`-annotated classes is the only shape the plugin verifies, and it is a real
refactor with its own risk. What it would buy here: compile-time checking of a graph whose
entire purpose is to be checked at runtime by `checkModules()`. What it would cost: a
second, class-based copy of the platform bindings that `TestPlatformModuleParityTest`
would then have to keep in step with production. Compile-time verification of the
production graph already happens — `platformModule()` is verified at the three real entry
points.

## Rationale

A warning that fires on a test whose job is runtime validation is not a defect in the
wiring. It is a statement about the tool: the Koin compiler analyses `@Module` classes and
statically-shaped DSL, and a hand-built test graph with forty bindings and Room DAOs is
outside what it resolves. Suppressing the warning would mean hiding the fact; the honest
move is to say where it comes from and why it is tolerable *here* specifically.

The word "tolerable" is doing real work in that sentence, and it is bounded by two facts
that could change: the test is `@Tag("slow")` and runs in CI, and the production entry
points are verified. If this test ever stops running, the warning becomes a hole rather
than a redundancy.

## The measurement trap, recorded because it cost time

Two intermediate results in this investigation were wrong, both from the same cause: **the
Koin compiler plugin emits KOIN-W003 only when the affected source file is actually
recompiled.** Several probe runs added an unrelated new file, which left
`KoinGraphValidationTest.kt` un-recompiled, and the warning vanished from the output. That
absence read as "this shape is fine" and was pure artefact.

A negative result from a compiler warning is only meaningful with a forced recompile —
`touch` the file, or expect the run to prove nothing. The same trap bit three separate
times here, and the same class of error appears elsewhere in this repository: a gate that
checked nothing reported success twice in `check-test-task-inputs.py` on the same day.

## Consequences

- `AGENTS.md`'s DI section no longer claims a mapping it cannot deliver, and points at this
  ADR for the measured table.
- `ScopeIsolationTest` no longer asserts that the spread is the house pattern. Two of its
  three tests existed to document `*domainModule().toTypedArray()` as "the EXACT pattern
  used in domainModule()", which had become a false statement about production and a
  prescription for a banned shape. They now assert the composition form and cross-module
  resolution through it, which is the property that could actually regress.
- A future contributor who sees KOIN-W003 on a test entry point has a documented reason not
  to "fix" it by re-introducing a spread.

## Links

- `2026-09-27-di-module-aggregator-narrative` — why the module list is a value and not an
  `includes()` child scope
- `2026-10-04-test-execution-integrity` — the same principle at the level of test runs
- `AGENTS.md` — the DI section this corrects
- `shared/src/jvmTest/kotlin/com/singularity/todo/test/KoinGraphValidationTest.kt`,
  `shared/src/jvmTest/kotlin/com/singularity/todo/test/ScopeIsolationTest.kt`
