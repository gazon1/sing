---
name: speckit-engineering-implement
description: 'Apply an approved Engineering SDD plan — the only step that writes application code, and only what tasks.md authorizes. Seventh step, after the review-plan gate; cannot edit the approved spec, design or verification.'
disable-model-invocation: true
---

# Engineering SDD — Implement

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `openspec/changes/<slug>/spec.md`, `design.md`, `tasks.md`, `verification.md`
- Write: application code, tests, and `openspec/changes/<slug>/tasks.md` (checkboxes only)

## Goal

Apply the approved change. **Only** what `tasks.md` says, and no more.

### Order of work

1. Read `tasks.md` and list what you are about to do, in dependency order.
2. Implement tasks in that order. Check off each box as it completes.
3. Write the tests named by each task's `Verification:` line — they are part of the task, not a
   follow-up. A task whose acceptance criterion has no test is not finished.
4. Run the validation commands from `design.md` before reporting done.

### Project rules that are not optional

These are in `AGENTS.md` and enforced by detekt and gates. A change that violates them is rejected.

- `runBlocking` in production code — use `scope.launch`.
- No `*Blocking()` methods in repositories — suspend functions returning `Result<T>`.
- Fakes, not MockK, for state. MockK only for outgoing calls you assert on.
- No `stateIn` in a ViewModel. The pattern is `MutableStateFlow` + `scope.launch { }.collect { }`.
- No `viewModelScope` in production — an injected `AutoCloseableCoroutineScope`, added via
  `addCloseable` so it closes with the ViewModel.
- No pass-through CRUD use cases — `PassThroughUseCase` is a detekt rule.
- No `java.io.File` outside the `FileSystem` port.
- `factory { }` is **never** for a ViewModel — it leaks.
- `*domainModule().toTypedArray()` in `modules()` causes KOIN-W003. Compose with lists.
- A Room schema change needs `SCHEMA_VERSION` **and** an `AutoMigration`. Never change the schema
  coarsely.
- Every new test class carries `@Tag("fast")` or `@Tag("slow")`, or CI silently skips it.
- No `delay(N)` as test synchronization — `advanceUntilIdle()`, or the test is flaky.

### If the approved plan turns out to be wrong

**Stop.** Do not silently widen the change, and do not edit `spec.md` to match what you built.

Report: which `AC-NN` or `INV-NN` cannot be met, what the code actually does, and what would have to
change in `spec.md` or `design.md`. A mismatch discovered during implementation is information the
user needs — a quietly widened scope is information they are denied.

## Forbidden

- Editing `spec.md`, `design.md`, `verification.md` or `model.md`. These are approved; if they are
  wrong, that is a finding to report, not a thing to fix yourself.
- Doing work no task asks for. No drive-by refactors, no "while I was in there", no new abstraction.
- Adding a public API that `spec.md` did not require.
- Deleting or rewriting a failing test to make the suite green. Fix the code, or report it.
- Skipping the `@Tag` on a new test class.
- Marking a task complete when its verification has not been run or has failed.

## Done When

- Every task in `tasks.md` is either checked off with its test written, or explicitly reported as
  blocked with a reason.
- Every test named by a `Verification:` line exists and is tagged.
- The validation commands from `design.md` have been run, and their real output is reported — pass
  or fail.
- Any deviation from `tasks.md` is listed, not hidden.