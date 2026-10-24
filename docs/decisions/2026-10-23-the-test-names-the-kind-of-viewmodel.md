---
title: "The test names the kind of ViewModel"
date: 2026-10-23
status: accepted
tags: [testing, viewmodel, coroutines, harness]
---

# The test names the kind of ViewModel

## Context

The harness convention has one exemption list, `EXEMPT_VM_TEST`, and every
entry in it is a test file that builds its own scope instead of handing the
choice to the harness. Each entry carries a reason, and the gate checks both
ways: a file that is listed must still hand-roll a scope, and a file that
hand-rolls a scope must be listed.

Two files were in that list — the agenda's own tests — and the reason they
gave for the first one was this:

> `TestVmContext` does not expose raw VM or backgroundScope for event collection

That reason had stopped being true. `TestVmContext` exposes the raw VM
(`val vm`) and the background scope (`val backgroundScope`), and the previous
round migrated the two `SavedAgenda` files off the list because of it. What was
left was a reason that pointed at a gap which was no longer there — and a gate
whose exemptions are read by the next person who has to make a decision.

The real reason those two files hand-rolled a scope was in the second entry,
and it was true: the agenda collects a clock flow that never ends, so the
ViewModel's work cannot be the test's work, and the test's virtual clock can
never be advanced to idle.

That is exactly what `VmScope.Background` is for. The harness takes a *name*
for the kind of ViewModel under test — one whose work ends, or one whose
collector never ends — and builds the scope that kind needs.

## Idea

Say what kind of ViewModel the test is testing, and let the harness build the
scope.

## Decision

**The agenda's tests use the harness, with `VmScope.Background`.**

```kotlin
private fun TestScope.createVm(): AgendaViewModel = testVm(
    stateAccessor = { vm: AgendaViewModel -> vm.stateFlow },
    vmScope = VmScope.Background,
) { scope ->
    AgendaViewModel(
        deps = agendaDeps(...),
        definition = AgendaPresets.Inbox,
        scope = scope,
    )
}.vm
```

**The exemption list is empty.** No test file hand-rolls a scope any more, so
there is nothing to be exempt from. The map stays — with its two-way check —
because the next file that wants to hand-roll a scope has to be able to say
why, in writing, and the gate has to hold it to that.

**The stale exemption ADR is superseded.** Its "future work" — migrate the
files when the harness grows the access they need — is done: the access grew,
and the files were migrated. Its body is left as it was written, because a
closed decision is a record of what was thought then.

## Rationale

The reason in the exemption was a description of a gap in the harness, and it
was the wrong kind of reason: it said "the harness cannot do this", and the
harness could do it. A reason that stops being true is worse than no reason,
because it is read as a fact about the code.

The reason that was true — the clock flow that never ends — is a fact about
the *ViewModel*, not about the harness. And a fact about the ViewModel is
exactly what the harness asks for: `VmScope.Background` says "this ViewModel's
work does not hold the test open", and the harness builds the scope that does
not hold the test open.

The tests did not get simpler — they got honest. The agenda's tests now say
what kind of ViewModel they are testing, and the scope follows from that.

## Consequences

- **No test file builds its own scope.** The exemption list is empty, and the
  gate that keeps it empty still runs.
- **The agenda's tests keep their meaning.** The clock flow still never ends;
  the scope is still not the test's; the tests still assert what they asserted.
- **The next file that hand-rolls a scope has to say why**, and the reason is
  checked against the source.
- **`VmScope.Background` is used.** It was added for this case and had no
  user; now it has one.

## Links

- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/AgendaViewModelTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/AgendaBulkCloseTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`
  — the harness and `VmScope`.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SharedHarnessConventionsTest.kt`
  — the gate and the (now empty) exemption list.
- `docs/decisions/2026-10-27-vm-test-scope-exemption.md`
  — the exemption this supersedes.
- `docs/decisions/2026-10-23-the-harness-gives-the-scope.md`
  — the harness this closes the loop of.
