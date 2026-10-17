---
title: "VM test scope exemption: SavedAgendaViewModelTest and SavedAgendaListViewModelTest"
date: 2026-10-27
status: accepted
tags: [testing, viewmodel, coroutines]
---

# VM test scope exemption: `SavedAgendaViewModelTest` and `SavedAgendaListViewModelTest`

## Context

The `SharedHarnessConventionsTest.runVmTest is used for ViewModel tests` scanner detects the
pattern `runTest { ... val XScope = AutoCloseableCoroutineScope(...) }` — hand-rolled scope
creation instead of the `runVmTest { }` / `testVm { }` helper.

Two existing test files trigger this scanner:

- `SavedAgendaViewModelTest.kt`
- `SavedAgendaListViewModelTest.kt`

Both files use `backgroundScope.launch { vm.events.collect { events += it } }` for
event-listening tests, where `backgroundScope` is the `Job` of the `runTest { }` scope.
`TestVmContext` (returned by `testVm { }`) does not expose `backgroundScope` or the raw VM,
making it impossible to migrate these two tests to `testVm { }` without a deeper
infrastructure change.

## Decision

Add `SavedAgendaViewModelTest.kt` and `SavedAgendaListViewModelTest.kt` to
`SharedHarnessConventionsTest.EXEMPT_VM_TEST` with documented reasons. Add the two-way
stale-exemption check test.

Do **not** attempt a `testVm { }` migration at this time: the scanner's regex matches across
`runTest { testVm({ ... val scope = AutoCloseableCoroutineScope(...) }) }` because
`DOT_MATCHES_ALL` + `[^}]*` allows `{` and `}` inside the matched span, so the nested
factory lambda's scope creation is still detected.

## Rationale

**`backgroundScope` is a `Job`, not a `CoroutineScope`.** `testVm { }` returns a
`TestVmContext` that wraps the VM and state, but neither `backgroundScope` nor the raw VM
is accessible from `TestVmContext`. The event-collection pattern
`backgroundScope.launch { vm.events.collect { ... } }` is therefore unavailable.

**The scanner can't distinguish nested scope creation.** The `DOT_MATCHES_ALL` regex
matches the entire `runTest { }` body as one span, including factory lambdas passed to
`testVm { }`. Migrating to `testVm { }` does not suppress the violation — the scope
creation inside the factory lambda is still matched.

**These tests are canonical, not aberrant.** Both files are mature, well-documented tests
that correctly exercise the VM's full surface including event flows. The exemption
preserves existing good tests rather than forcing a premature rewrite.

**The stale-exemption guard prevents silent rot.** The two-way check ensures that if
either test is later refactored to use `testVm { }` or another pattern that eliminates
`AutoCloseableCoroutineScope`, the exemption entry is flagged for removal.

## Consequences

**Positive:**
- The convention scanner no longer produces false failures for these two files.
- The two-way stale check ensures exemptions don't outlive their necessity.
- The reason is documented inline, so future maintainers understand why.

**Negative:**
- Two test files remain in the hand-rolled scope pattern. If `TestVmContext` is
  extended to expose `backgroundScope` and the raw VM in the future, these exemptions
  should be removed and the files migrated.

## Future Work

When `TestVmContext` or `testVm { }` grows support for:
1. Access to the raw VM instance
2. A `backgroundScope`-equivalent for fire-and-forget event collection

...migrate `SavedAgendaViewModelTest` and `SavedAgendaListViewModelTest` to `testVm { }`
and remove the corresponding `EXEMPT_VM_TEST` entries.

## Links

- `SharedHarnessConventionsTest.kt` — `shared/src/jvmTest/kotlin/com/singularity/todo/arch/`
- `SavedAgendaViewModelTest.kt` — `shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/`
- `SavedAgendaListViewModelTest.kt` — `shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/`
- `TestVmInfrastructure.kt` — `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/`
- Issue #708 (related Genui corpus resource gap)
