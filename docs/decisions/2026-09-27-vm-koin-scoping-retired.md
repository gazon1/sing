---
title: Retire `singularity-todo-vm-koin-scoping`
date: 2026-09-27
status: accepted
tags: [koin, viewmodel, skills, documentation]
---

# Retire `singularity-todo-vm-koin-scoping`

## Context

`singularity-todo-vm-koin-scoping` (193 lines) claimed:

> ALWAYS use explicit `viewModel { }` lambda (never `viewModelOf` due to constructor
> ambiguity with `CoroutineScope`/`SharingStarted`)

That directly contradicted `AGENTS.md`, `singularity-todo-koin-dsl` and
`singularity-todo-koin-overview`, which all prefer `viewModelOf(::Vm)`. An agent loading
both got opposite instructions for the same line of code.

The skill's technical argument was not baseless — but it was stated far more broadly than
the code supports:

- `SharingStarted` appears **zero** times in the DI modules, so the "constructor ambiguity
  with `SharingStarted`" scenario cannot occur here.
- Actual registrations: **32** `viewModel { }` vs **1** `viewModelOf` (in `TagsDiModule.kt`).
- `factory { *ViewModel }` — **0** occurrences, so the memory-leak warning was already
  being followed everywhere.
- Only **1** ViewModel in the whole feature tree has a secondary constructor. The canonical
  testable-VM shape is a single constructor with a defaulted
  `scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope()` — and
  `viewModelOf` handles that fine, which is why `TagGroupsViewModel` works with it.

The residual truth is narrower and worth keeping: for a VM that genuinely has two
constructors, or whose dependencies need naming at the registration site, use an explicit
`viewModel { }` block. `feature/agenda/AgendaDiModule.kt` carries an in-code note about
this and does not need the skill to restate it.

## Idea

One source of truth for VM registration. The rule already lives in `AGENTS.md` (the file
every agent loads automatically); the skill restated it with a stronger, less accurate
claim and thereby created the contradiction.

## Decision

- Retire `singularity-todo-vm-koin-scoping`. The directory becomes
  `singularity-todo-vm-koin-scoping-RETIRED/` with a stub that points at `AGENTS.md` and
  `singularity-todo-vm-migration-playbook`.
- Canonical rules stay in `AGENTS.md`:

  | DSL | When |
  |---|---|
  | `viewModelOf(::Vm)` | VM with no runtime parameters and a single constructor (prefer) |
  | `viewModel { (p: Param) -> Vm(get(), p) }` | VM with runtime parameters |
  | `koinViewModel()` / `koinViewModel { parametersOf(p) }` | injection in a Composable |
  | `factory { Vm(...) }` | **never** for a ViewModel — memory leak |

- `AGENTS.md` drops its pointer to the retired skill; the two files no longer overlap.

## Rationale

`AGENTS.md` is loaded into every session, so a rule stated there cannot be missed. A skill
stating the same rule differently can only cause harm. The skill's distinctive content —
the `koinViewModel` vs `koinInject` distinction and `parametersOf` usage — is already
covered by `singularity-todo-koin-di` and by the table in `AGENTS.md`, so nothing is lost.

## Consequences

- `AGENTS.md`, `koin-dsl` and `koin-overview` now agree; the contradiction is gone.
- If a future VM genuinely needs constructor-overload resolution, that constraint is
  discoverable from the compiler error and from the existing in-code notes — it does not
  justify a skill that overrides the default rule for every VM.
- The retired directory stays in the tree (rather than being deleted) so the history
  remains greppable and anyone who remembers the old name finds the pointer.

## Links

- `AGENTS.md` — the canonical VM registration table
- `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` — original decision
- `docs/decisions/2026-09-27-di-module-aggregator-narrative.md` — where the modules live
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/AgendaDiModule.kt:57` —
  in-code note on the constructor-overload case
