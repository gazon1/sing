---
name: singularity-todo-vm-koin-scoping-RETIRED
description: RETIRED. Formerly argued that viewModelOf must never be used. Superseded — the canonical ViewModel registration rules live in AGENTS.md. Load singularity-todo-koin-di or singularity-todo-vm-migration-playbook instead.
---

# RETIRED — ViewModel DI Scope

This skill is **retired** as of 2026-09-27.

It argued that `viewModelOf` must never be used because of constructor ambiguity with
`CoroutineScope`/`SharingStarted`. In practice `SharingStarted` appears nowhere in this
project's DI modules, and the canonical testable-ViewModel shape is a single constructor
with a defaulted `scope` parameter — which `viewModelOf` handles correctly
(`TagsDiModule.kt` does exactly that).

The blanket ban was wrong in general and created a direct contradiction with `AGENTS.md`,
which every agent loads automatically.

**Where the rules live now:**

- `AGENTS.md` — the canonical `viewModelOf` / `viewModel { }` / `koinViewModel` table.
- `singularity-todo-koin-di` — `singleOf` / `factoryOf` gotchas, `koinBridge`.
- `singularity-todo-vm-migration-playbook` — migrating a VM to the canonical shape.

The one case the old skill warned about is still real and is already documented in code:
a ViewModel with genuinely two constructors needs an explicit `viewModel { }` block — see
the note in `feature/agenda/AgendaDiModule.kt`.

Decision record: `docs/decisions/2026-09-27-vm-koin-scoping-retired.md`.
